package zio.kafka.consumer.internal

import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RebalanceInProgressException
import zio.kafka.consumer.Consumer.OffsetRetrieval
import zio.kafka.consumer._
import zio.kafka.consumer.diagnostics.DiagnosticEvent.Finalization
import zio.kafka.consumer.diagnostics.{ DiagnosticEvent, Diagnostics }
import zio.kafka.consumer.internal.ConsumerAccess.ByteArrayKafkaConsumer
import zio.kafka.consumer.internal.RunloopAccess.PartitionAssignment
import zio.stream._
import zio.{ Task, _ }

import java.util
import scala.jdk.CollectionConverters._

//noinspection SimplifyWhenInspection
private[consumer] final class Runloop private (
  runtime: Runtime[Any],
  getConsumerGroupMetadataIfAny: UIO[Option[ConsumerGroupMetadata]],
  doSeekForNewPartitions: (Set[TopicPartition], ByteArrayKafkaConsumer) => Task[Set[TopicPartition]],
  consumerAccess: ConsumerAccess,
  pollTimeout: Duration,
  commandQueue: Queue[RunloopCommand],
  lastRebalanceEvent: Ref.Synchronized[Option[Runloop.RebalanceEvent]],
  partitionsHub: Hub[Take[Throwable, PartitionAssignment]],
  diagnostics: Diagnostics,
  userRebalanceListener: RebalanceListener,
  restartStreamsOnRebalancing: Boolean,
  currentStateRef: Ref[State]
) {

  private def newPartitionStream(tp: TopicPartition): UIO[PartitionStreamControl] =
    PartitionStreamControl.newPartitionStream(tp, commandQueue, diagnostics)

  def stopConsumption: UIO[Unit] =
    ZIO.logDebug("stopConsumption called") *>
      commandQueue.offer(RunloopCommand.StopAllStreams).unit

  private[consumer] def shutdown: UIO[Unit] =
    ZIO.logDebug(s"Shutting down runloop initiated") *>
      commandQueue
        .offerAll(
          Chunk(
            RunloopCommand.StopSubscription,
            RunloopCommand.StopAllStreams,
            RunloopCommand.StopRunloop
          )
        )
        .unit

  private[internal] def addSubscription(subscription: Subscription): UIO[Unit] =
    commandQueue.offer(RunloopCommand.AddSubscription(subscription)).unit

  private[internal] def removeSubscription(subscription: Subscription): UIO[Unit] =
    commandQueue.offer(RunloopCommand.RemoveSubscription(subscription)).unit

  private val rebalanceListener: RebalanceListener = {
    val emitDiagnostics = RebalanceListener(
      (assigned, _) => diagnostics.emit(DiagnosticEvent.Rebalance.Assigned(assigned)),
      (revoked, _) => diagnostics.emit(DiagnosticEvent.Rebalance.Revoked(revoked)),
      (lost, _) => diagnostics.emit(DiagnosticEvent.Rebalance.Lost(lost))
    )

    def restartStreamsRebalancingListener = RebalanceListener(
      onAssigned = (assigned, _) =>
        ZIO.logDebug("Rebalancing completed") *>
          lastRebalanceEvent.updateZIO {
            case None =>
              ZIO.some(Runloop.RebalanceEvent.Assigned(assigned))
            case Some(Runloop.RebalanceEvent.Revoked(revokeResult)) =>
              ZIO.some(Runloop.RebalanceEvent.RevokedAndAssigned(revokeResult, assigned))
            case Some(_) =>
              ZIO.fail(new IllegalStateException(s"Multiple onAssigned calls on rebalance listener"))
          },
      onRevoked = (_, _) =>
        ZIO.logDebug("Rebalancing started") *>
          currentStateRef.get.flatMap { state =>
            // End all streams
            endRevokedPartitions(
              state.pendingRequests,
              state.assignedStreams,
              isRevoked = _ => true
            ).flatMap { result =>
              lastRebalanceEvent.updateZIO {
                case None =>
                  ZIO.some(Runloop.RebalanceEvent.Revoked(result))
                case _ =>
                  ZIO.fail(
                    new IllegalStateException(
                      s"onRevoked called on rebalance listener with pending assigned event"
                    )
                  )
              }
            }
          }
    )

    if (restartStreamsOnRebalancing) {
      emitDiagnostics ++ restartStreamsRebalancingListener ++ userRebalanceListener
    } else {
      emitDiagnostics ++ userRebalanceListener
    }
  }

  private val commit: Map[TopicPartition, Long] => Task[Unit] =
    offsets =>
      for {
        p <- Promise.make[Throwable, Unit]
        _ <- commandQueue.offer(RunloopCommand.Commit(offsets, p)).unit
        _ <- diagnostics.emit(DiagnosticEvent.Commit.Started(offsets))
        _ <- p.await
      } yield ()

  private def doCommit(cmd: RunloopCommand.Commit): UIO[Unit] = {
    val offsets   = cmd.offsets.map { case (tp, offset) => tp -> new OffsetAndMetadata(offset + 1) }
    val cont      = (e: Exit[Throwable, Unit]) => cmd.cont.done(e).asInstanceOf[UIO[Unit]]
    val onSuccess = cont(Exit.unit) <* diagnostics.emit(DiagnosticEvent.Commit.Success(offsets))
    val onFailure: Throwable => UIO[Unit] = {
      case _: RebalanceInProgressException =>
        ZIO.logDebug(s"Rebalance in progress, retrying commit for offsets $offsets") *>
          commandQueue.offer(cmd).unit
      case err =>
        cont(Exit.fail(err)) <* diagnostics.emit(DiagnosticEvent.Commit.Failure(offsets, err))
    }
    val callback =
      new OffsetCommitCallback {
        override def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception): Unit =
          Unsafe.unsafe { implicit u =>
            runtime.unsafe.run(if (exception eq null) onSuccess else onFailure(exception)).getOrThrowFiberFailure()
          }
      }

    // We don't wait for the completion of the commit here, because it
    // will only complete once we poll again.
    consumerAccess.runloopAccess { c =>
      ZIO
        .attempt(c.commitAsync(offsets.asJava, callback))
        .catchAll(onFailure)
    }
  }

  /**
   * Does all needed to end revoked partitions:
   *   1. Complete the revoked assigned streams 2. Remove from the list of pending requests
   *
   * @return
   *   New pending requests, new active assigned streams
   */
  private def endRevokedPartitions(
    pendingRequests: Chunk[RunloopCommand.Request],
    assignedStreams: Chunk[PartitionStreamControl],
    isRevoked: TopicPartition => Boolean
  ): UIO[Runloop.RevokeResult] = {
    val (revokedStreams, newAssignedStreams) =
      assignedStreams.partition(control => isRevoked(control.tp))

    ZIO
      .foreachDiscard(revokedStreams)(_.end())
      .as(
        Runloop.RevokeResult(
          pendingRequests = pendingRequests.filter(req => !isRevoked(req.tp)),
          assignedStreams = newAssignedStreams
        )
      )
  }

  /**
   * Offer records retrieved from poll() call to the streams.
   *
   * @return
   *   Remaining pending requests
   */
  private def offerRecordsToStreams(
    partitionStreams: Chunk[PartitionStreamControl],
    pendingRequests: Chunk[RunloopCommand.Request],
    ignoreRecordsForTps: Set[TopicPartition],
    polledRecords: ConsumerRecords[Array[Byte], Array[Byte]]
  ): UIO[Runloop.FulfillResult] = {
    type Record = CommittableRecord[Array[Byte], Array[Byte]]

    // The most efficient way to get the records from [[ConsumerRecords]] per
    // topic-partition, is by first getting the set of topic-partitions, and
    // then requesting the records per topic-partition.
    val tps           = polledRecords.partitions().asScala.toSet -- ignoreRecordsForTps
    val fulfillResult = Runloop.FulfillResult(pendingRequests = pendingRequests.filter(req => !tps.contains(req.tp)))
    val streams =
      if (tps.isEmpty) Chunk.empty else partitionStreams.filter(streamControl => tps.contains(streamControl.tp))

    if (streams.isEmpty) ZIO.succeed(fulfillResult)
    else {
      for {
        consumerGroupMetadata <- getConsumerGroupMetadataIfAny
        committableRecords = {
          val acc             = ChunkBuilder.make[(PartitionStreamControl, Chunk[Record])](streams.size)
          val streamsIterator = streams.iterator
          while (streamsIterator.hasNext) {
            val streamControl = streamsIterator.next()
            val tp            = streamControl.tp
            val records       = polledRecords.records(tp)
            if (!records.isEmpty) {
              val builder  = ChunkBuilder.make[Record](records.size())
              val iterator = records.iterator()
              while (iterator.hasNext) {
                val consumerRecord = iterator.next()
                builder +=
                  CommittableRecord[Array[Byte], Array[Byte]](
                    record = consumerRecord,
                    commitHandle = commit,
                    consumerGroupMetadata = consumerGroupMetadata
                  )
              }
              acc += (streamControl -> builder.result())
            }
          }
          acc.result()
        }
        _ <- ZIO
               .foreachDiscard(committableRecords) { case (streamControl, records) =>
                 streamControl.offerRecords(records)
               }
      } yield fulfillResult
    }
  }

  /**
   * Pause partitions for which there is no demand and resume those for which there is now demand. Optimistically resume
   * partitions that are likely to need more data in the next poll.
   */
  private def resumeAndPausePartitions(
    c: ByteArrayKafkaConsumer,
    requestedPartitions: Set[TopicPartition],
    streams: Chunk[PartitionStreamControl]
  ): Unit = {
    val resumeTps = new java.util.ArrayList[TopicPartition](streams.size)
    val pauseTps  = new java.util.ArrayList[TopicPartition](streams.size)
    val iterator  = streams.iterator
    while (iterator.hasNext) {
      val stream   = iterator.next()
      val tp       = stream.tp
      val toResume = requestedPartitions.contains(tp) || stream.optimisticResume
      if (toResume) resumeTps.add(tp) else pauseTps.add(tp)
      stream.addPollHistory(toResume)
    }
    if (!resumeTps.isEmpty) c.resume(resumeTps)
    if (!pauseTps.isEmpty) c.pause(pauseTps)
  }

  private def handlePoll(state: State): Task[State] = {

    def poll: Task[
      (
        Set[TopicPartition],
        Chunk[RunloopCommand.Request],
        Chunk[PartitionStreamControl],
        ConsumerRecords[Array[Byte], Array[Byte]],
        Set[TopicPartition]
      )
    ] =
      consumerAccess.runloopAccess { c =>
        ZIO.suspend {
          val prevAssigned        = c.assignment().asScala.toSet
          val requestedPartitions = state.pendingRequests.map(_.tp).toSet

          resumeAndPausePartitions(c, requestedPartitions, state.assignedStreams)

          val polledRecords = {
            val records = c.poll(pollTimeout)
            if (records eq null) ConsumerRecords.empty[Array[Byte], Array[Byte]]() else records
          }

          val currentAssigned = c.assignment().asScala.toSet
          val newlyAssigned   = currentAssigned -- prevAssigned

          for {
            ignoreRecordsForTps <- doSeekForNewPartitions.apply(newlyAssigned, c)

            rebalanceEvent <- lastRebalanceEvent.getAndSet(None)

            revokeResult <- rebalanceEvent match {
                              case Some(Runloop.RebalanceEvent.Revoked(result)) =>
                                // If we get here, `restartStreamsOnRebalancing == true`
                                // Use revoke result from endRevokedPartitions that was called previously in the rebalance listener
                                ZIO.succeed(result)
                              case Some(Runloop.RebalanceEvent.RevokedAndAssigned(result, _)) =>
                                // If we get here, `restartStreamsOnRebalancing == true`
                                // Use revoke result from endRevokedPartitions that was called previously in the rebalance listener
                                ZIO.succeed(result)
                              case Some(Runloop.RebalanceEvent.Assigned(_)) =>
                                // If we get here, `restartStreamsOnRebalancing == true`
                                // endRevokedPartitions was not called yet in the rebalance listener,
                                // and all partitions should be revoked
                                endRevokedPartitions(
                                  state.pendingRequests,
                                  state.assignedStreams,
                                  isRevoked = _ => true
                                )
                              case None =>
                                // End streams for partitions that are no longer assigned
                                endRevokedPartitions(
                                  state.pendingRequests,
                                  state.assignedStreams,
                                  isRevoked = (tp: TopicPartition) => !currentAssigned.contains(tp)
                                )
                            }

            startingTps = rebalanceEvent match {
                            case Some(_) =>
                              // If we get here, `restartStreamsOnRebalancing == true`,
                              // some partitions were revoked and/or assigned and
                              // all already assigned streams were ended.
                              // Therefore, all currently assigned tps are starting,
                              // either because they are restarting, or because they
                              // are new.
                              currentAssigned
                            case None =>
                              newlyAssigned
                          }

            _ <- diagnostics.emit {
                   val providedTps = polledRecords.partitions().asScala.toSet
                   DiagnosticEvent.Poll(
                     tpRequested = requestedPartitions,
                     tpWithData = providedTps,
                     tpWithoutData = requestedPartitions -- providedTps
                   )
                 }

          } yield (
            startingTps,
            revokeResult.pendingRequests,
            revokeResult.assignedStreams,
            polledRecords,
            ignoreRecordsForTps
          )
        }
      }

    for {
      _ <-
        ZIO.logDebug(
          s"Starting poll with ${state.pendingRequests.size} pending requests and ${state.pendingCommits.size} pending commits"
        )
      _                                                                                   <- currentStateRef.set(state)
      (startingTps, pendingRequests, assignedStreams, polledRecords, ignoreRecordsForTps) <- poll
      startingStreams <- if (startingTps.isEmpty) ZIO.succeed(Chunk.empty[PartitionStreamControl])
                         else {
                           ZIO
                             .foreach(Chunk.fromIterable(startingTps))(newPartitionStream)
                             .tap { newStreams =>
                               ZIO.logDebug(s"Offering partition assignment $startingTps") *>
                                 partitionsHub.publish(Take.chunk(Chunk.fromIterable(newStreams.map(_.tpStream))))
                             }
                         }
      runningStreams <- ZIO.filter(assignedStreams)(_.isRunning)
      updatedStreams = runningStreams ++ startingStreams
      fulfillResult <- offerRecordsToStreams(
                         updatedStreams,
                         pendingRequests,
                         ignoreRecordsForTps,
                         polledRecords
                       )
      updatedPendingCommits <- ZIO.filter(state.pendingCommits)(_.isPending)
    } yield state.copy(
      pendingRequests = fulfillResult.pendingRequests,
      pendingCommits = updatedPendingCommits,
      assignedStreams = updatedStreams
    )
  }

  private def handleCommand(state: State, cmd: RunloopCommand.StreamCommand): Task[State] = {
    def doChangeSubscription(newSubscriptionState: SubscriptionState): Task[State] =
      applyNewSubscriptionState(newSubscriptionState).flatMap { newAssignedStreams =>
        val newState = state.copy(
          assignedStreams = state.assignedStreams ++ newAssignedStreams,
          subscriptionState = newSubscriptionState
        )
        if (newSubscriptionState.isSubscribed) ZIO.succeed(newState)
        else
          // End all streams and pending requests
          endRevokedPartitions(
            newState.pendingRequests,
            newState.assignedStreams,
            isRevoked = _ => true
          ).map { revokeResult =>
            newState.copy(
              pendingRequests = revokeResult.pendingRequests,
              assignedStreams = revokeResult.assignedStreams
            )
          }
      }

    cmd match {
      case req: RunloopCommand.Request => ZIO.succeed(state.addRequest(req))
      case cmd: RunloopCommand.Commit  => doCommit(cmd).as(state.addCommit(cmd))
      case RunloopCommand.AddSubscription(newSubscription) =>
        state.subscriptionState match {
          case SubscriptionState.NotSubscribed =>
            val newSubState =
              SubscriptionState.Subscribed(subscriptions = Set(newSubscription), union = newSubscription)
            doChangeSubscription(newSubState)
          case SubscriptionState.Subscribed(existingSubscriptions, _) =>
            val subs = NonEmptyChunk.fromIterable(newSubscription, existingSubscriptions)

            Subscription.unionAll(subs) match {
              case None => ZIO.fail(InvalidSubscriptionUnion(subs))
              case Some(union) =>
                val newSubState =
                  SubscriptionState.Subscribed(
                    subscriptions = existingSubscriptions + newSubscription,
                    union = union
                  )
                doChangeSubscription(newSubState)
            }
        }
      case RunloopCommand.RemoveSubscription(subscription) =>
        state.subscriptionState match {
          case SubscriptionState.NotSubscribed => ZIO.succeed(state)
          case SubscriptionState.Subscribed(existingSubscriptions, _) =>
            val newUnion: Option[(Subscription, NonEmptyChunk[Subscription])] =
              NonEmptyChunk
                .fromIterableOption(existingSubscriptions - subscription)
                .flatMap(subs => Subscription.unionAll(subs).map(_ -> subs))

            newUnion match {
              case Some((union, newSubscriptions)) =>
                val newSubState =
                  SubscriptionState.Subscribed(subscriptions = newSubscriptions.toSet, union = union)
                doChangeSubscription(newSubState)
              case None =>
                ZIO.logDebug(s"Unsubscribing kafka consumer") *>
                  doChangeSubscription(SubscriptionState.NotSubscribed)
            }
        }
      case RunloopCommand.StopSubscription => doChangeSubscription(SubscriptionState.NotSubscribed)
      case RunloopCommand.StopAllStreams =>
        for {
          _ <- ZIO.logDebug("Stop all streams initiated")
          _ <- ZIO.foreachDiscard(state.assignedStreams)(_.end())
          _ <- partitionsHub.publish(Take.end)
          _ <- ZIO.logDebug("Stop all streams done")
        } yield state.copy(pendingRequests = Chunk.empty)
    }
  }

  private def applyNewSubscriptionState(
    newSubscriptionState: SubscriptionState
  ): Task[Chunk[PartitionStreamControl]] =
    consumerAccess.runloopAccess { c =>
      newSubscriptionState match {
        case SubscriptionState.NotSubscribed =>
          ZIO
            .attempt(c.unsubscribe())
            .as(Chunk.empty)
        case SubscriptionState.Subscribed(_, Subscription.Pattern(pattern)) =>
          val rc = RebalanceConsumer.Live(consumerAccess)
          ZIO
            .attempt(c.subscribe(pattern.pattern, rebalanceListener.toKafka(runtime, rc)))
            .as(Chunk.empty)
        case SubscriptionState.Subscribed(_, Subscription.Topics(topics)) =>
          val rc = RebalanceConsumer.Live(consumerAccess)
          ZIO
            .attempt(c.subscribe(topics.asJava, rebalanceListener.toKafka(runtime, rc)))
            .as(Chunk.empty)
        case SubscriptionState.Subscribed(_, Subscription.Manual(topicPartitions)) =>
          // For manual subscriptions we have to do some manual work before starting the run loop
          for {
            _                <- ZIO.attempt(c.assign(topicPartitions.asJava))
            _                <- doSeekForNewPartitions(topicPartitions, c)
            partitionStreams <- ZIO.foreach(Chunk.fromIterable(topicPartitions))(newPartitionStream)
            _                <- partitionsHub.publish(Take.chunk(partitionStreams.map(_.tpStream)))
          } yield partitionStreams
      }
    }

  /**
   * Poll behavior:
   *   - Run until stop is set to true
   *   - Process commands as soon as they are queued, unless in the middle of polling
   *   - Process all currently queued commands before polling instead of one by one
   *   - Immediately after polling, if there are available commands, process them instead of waiting until some periodic
   *     trigger
   *   - Poll only when subscribed (leads to exceptions from the Apache Kafka Consumer if not)
   *   - Poll continuously when there are (still) unfulfilled requests or pending commits
   *   - Poll periodically when we are subscribed but do not have assigned streams yet. This happens after
   *     initialization and rebalancing
   */
  def run: ZIO[Scope, Throwable, Any] = {
    import ZStreamExtensions.StreamOps

    ZStream
      .fromQueue(commandQueue)
      .takeWhile(_ != RunloopCommand.StopRunloop)
      .runFoldChunksDiscardZIO(State.initial) { (state, commands) =>
        for {
          _ <- ZIO.logDebug(s"Processing ${commands.size} commands: ${commands.mkString(",")}")
          streamCommands = commands.collect { case cmd: RunloopCommand.StreamCommand => cmd }
          stateAfterCommands <- ZIO.foldLeft(streamCommands)(state)(handleCommand)

          updatedStateAfterPoll <- if (stateAfterCommands.shouldPoll) handlePoll(stateAfterCommands)
                                   else ZIO.succeed(stateAfterCommands)
          // Immediately poll again, after processing all new queued commands
          _ <- if (updatedStateAfterPoll.shouldPoll) commandQueue.offer(RunloopCommand.Poll) else ZIO.unit
        } yield updatedStateAfterPoll
      }
      .tapErrorCause(cause => ZIO.logErrorCause("Error in Runloop", cause))
      .onError(cause => partitionsHub.offer(Take.failCause(cause)))
  }
}

private[internal] object Runloop {
  type ByteArrayCommittableRecord = CommittableRecord[Array[Byte], Array[Byte]]

  private final case class RevokeResult(
    pendingRequests: Chunk[RunloopCommand.Request],
    assignedStreams: Chunk[PartitionStreamControl]
  )
  private final case class FulfillResult(
    pendingRequests: Chunk[RunloopCommand.Request]
  )

  private sealed trait RebalanceEvent
  private object RebalanceEvent {
    final case class Revoked(revokeResult: Runloop.RevokeResult)  extends RebalanceEvent
    final case class Assigned(newlyAssigned: Set[TopicPartition]) extends RebalanceEvent
    final case class RevokedAndAssigned(
      revokeResult: Runloop.RevokeResult,
      newlyAssigned: Set[TopicPartition]
    ) extends RebalanceEvent
  }

  // Internal parameters, should not be necessary to tune
  private final val commandQueueSize: Int = 1024

  private val _emptyZioSet: UIO[Set[Any]]                       = ZIO.succeed(Set.empty)
  private def emptyZioSet[A]: UIO[Set[A]]                       = _emptyZioSet.asInstanceOf[UIO[Set[A]]]
  private val _IdentityZioEmptySet: (Any, Any) => UIO[Set[Any]] = (_: Any, _: Any) => _emptyZioSet
  private def identityZioEmptySet[A, B, R]: (A, B) => UIO[Set[R]] =
    _IdentityZioEmptySet.asInstanceOf[(A, B) => UIO[Set[R]]]

  def make(
    hasGroupId: Boolean,
    consumerAccess: ConsumerAccess,
    pollTimeout: Duration,
    diagnostics: Diagnostics,
    offsetRetrieval: OffsetRetrieval,
    userRebalanceListener: RebalanceListener,
    restartStreamsOnRebalancing: Boolean,
    partitionsHub: Hub[Take[Throwable, PartitionAssignment]]
  ): ZIO[Scope, Throwable, Runloop] =
    for {
      _                  <- ZIO.addFinalizer(diagnostics.emit(Finalization.RunloopFinalized))
      commandQueue       <- ZIO.acquireRelease(Queue.bounded[RunloopCommand](commandQueueSize))(_.shutdown)
      lastRebalanceEvent <- Ref.Synchronized.make[Option[Runloop.RebalanceEvent]](None)
      currentStateRef    <- Ref.make(State.initial)
      runtime            <- ZIO.runtime[Any]

      getConsumerGroupMetadataIfAny: UIO[Option[ConsumerGroupMetadata]] =
        if (hasGroupId) consumerAccess.runloopAccess(c => ZIO.attempt(c.groupMetadata())).option
        else ZIO.none

      doSeekForNewPartitions: ((Set[TopicPartition], ByteArrayKafkaConsumer) => Task[Set[TopicPartition]]) =
        offsetRetrieval match {
          case OffsetRetrieval.Auto(_) =>
            identityZioEmptySet[Set[TopicPartition], ByteArrayKafkaConsumer, TopicPartition]
          case OffsetRetrieval.Manual(getOffsets) =>
            (tps: Set[TopicPartition], c: ByteArrayKafkaConsumer) =>
              if (tps.isEmpty) emptyZioSet[TopicPartition]
              else
                getOffsets(tps).flatMap { offsets =>
                  ZIO.attempt {
                    val iterator = offsets.iterator
                    while (iterator.hasNext) {
                      val (tp, offset) = iterator.next()
                      c.seek(tp, offset)
                    }

                    tps
                  }
                }
        }

      runloop = new Runloop(
                  runtime = runtime,
                  getConsumerGroupMetadataIfAny = getConsumerGroupMetadataIfAny,
                  doSeekForNewPartitions = doSeekForNewPartitions,
                  consumerAccess = consumerAccess,
                  pollTimeout = pollTimeout,
                  commandQueue = commandQueue,
                  lastRebalanceEvent = lastRebalanceEvent,
                  partitionsHub = partitionsHub,
                  diagnostics = diagnostics,
                  userRebalanceListener = userRebalanceListener,
                  restartStreamsOnRebalancing = restartStreamsOnRebalancing,
                  currentStateRef = currentStateRef
                )
      _ <- ZIO.logDebug("Starting Runloop")

      // Run the entire loop on the a dedicated thread to avoid executor shifts
      executor <- RunloopExecutor.newInstance
      fiber    <- ZIO.onExecutor(executor)(runloop.run).forkScoped
      waitForRunloopStop = fiber.join.orDie

      _ <- ZIO.addFinalizer(
             ZIO.logDebug("Shutting down Runloop") *>
               runloop.shutdown *>
               waitForRunloopStop <*
               ZIO.logDebug("Shut down Runloop")
           )
    } yield runloop
}

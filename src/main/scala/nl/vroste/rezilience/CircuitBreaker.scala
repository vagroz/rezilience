package nl.vroste.rezilience
import zio.clock.Clock
import zio.stream.ZStream
import zio._

object CircuitBreaker {
  sealed trait CircuitBreakerCallError[+E]
  case object CircuitBreakerOpen       extends CircuitBreakerCallError[Nothing]
  case class WrappedError[E](error: E) extends CircuitBreakerCallError[E]

  // TODO custom definition of failure, i.e. not all E is failure to circuit breaker
  /**
   * Circuit Breaker protects external resources against overload under failure
   *
   * Operates in three states:
   *
   * - Closed (initial state / normal operation): calls are let through normally. Call failures increase
   *   a failure counter, call successes reset the failure counter to 0. When the
   *   failure count reaches the max, the circuit breaker is 'tripped' and set to the
   *   Open state. Note that after this switch, in-flight calls are not canceled. Their success
   *   or failure does not affect the circuit breaker anymore though.
   *
   * - Open: all calls fail fast with a [[CircuitBreakerOpen]] error. After the reset timeout,
   *   the states changes to HalfOpen
   *
   * - HalfOpen: the first call is let through. Meanwhile all other calls fail with a
   *   [[CircuitBreakerOpen]] error. If the first call succeeds, the state changes to
   *   Closed again (normal operation). If it fails, the state changes back to Open.
   *   The reset timeout is then increased exponentially.
   *
   * Notes:
   * - The maximum number of failures before tripping the circuit breaker is not absolute under
   *   concurrent execution. I.e. if you make 20 calls to a failing system in parallel via a circuit breaker
   *   with max 10 failures, the calls will be running concurrently. The circuit breaker will trip
   *   after 10 calls, but the remaining 10 that are in-flight will continue to run and fail as well.
   *
   *   TODO what to do if you want this kind of behavior, or should we make it an option?
   */
  trait Service {
    def call[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, CircuitBreakerCallError[E], A]
  }

  import zio.duration._
  import State._

  def make(
    maxFailures: Int,
    resetPolicy: Schedule[Clock, Any, Duration] = Schedule.exponential(1.second, 2.0),
    onStateChange: State => UIO[Unit] = _ => ZIO.unit
  ): ZManaged[Clock, Nothing, Service] =
    for {
      state          <- Ref.make[State](Closed).toManaged_
      halfOpenSwitch <- Ref.make[Boolean](true).toManaged_
      nrFailedCalls  <- Ref.make[Int](0).toManaged_
      scheduleState  <- (resetPolicy.initial >>= (Ref.make[resetPolicy.State](_))).toManaged_
      resetRequests  <- ZQueue.bounded[Unit](1).toManaged_
      _ <- ZStream
            .fromQueue(resetRequests)
            .mapM { _ =>
              for {
                s        <- scheduleState.get
                newState <- resetPolicy.update((), s)
                _        <- scheduleState.set(newState)
                _        <- halfOpenSwitch.set(true)
                _        <- state.set(HalfOpen)
                _        <- onStateChange(HalfOpen)
              } yield ()
            }
            .runDrain
            .forkManaged
    } yield new Service {
      val changeToOpen = state.set(Open) *>
        resetRequests.offer(()) <*
        onStateChange(Open)

      val changeToClosed = nrFailedCalls.set(0) *>
        (resetPolicy.initial >>= scheduleState.set) *> // Reset the reset schedule
        state.set(Closed) <*
        onStateChange(Closed)

      override def call[R, E, A](f: ZIO[R, E, A]): ZIO[R with Clock, CircuitBreakerCallError[E], A] =
        for {
          currentState <- state.get
          result <- currentState match {
                     case Closed =>
                       def onSuccess(x: A) = nrFailedCalls.set(0)
                       def onFailure(e: WrappedError[E]) =
                         (nrFailedCalls.updateAndGet(_ + 1) zip state.get) >>= {
                           case (failedCalls, currentState) =>
                             // The state may have already changed to Open or even HalfOpen.
                             // This can happen if we fire X calls in parallel where X >= 2 * maxFailures
                             ZIO.when(failedCalls == maxFailures && currentState == Closed)(changeToOpen)
                         }

                       f.mapError(WrappedError(_))
                         .tapBoth(onFailure, onSuccess)
                     case Open =>
                       ZIO.fail(CircuitBreakerOpen)
                     case HalfOpen =>
                       for {
                         isFirstCall <- halfOpenSwitch.getAndUpdate(_ => false)
                         result <- if (isFirstCall) {
                                    def onFailure(e: WrappedError[E]) = changeToOpen
                                    def onSuccess(x: A)               = changeToClosed

                                    f.mapError(WrappedError(_)).tapBoth(onFailure, onSuccess)
                                  } else {
                                    ZIO.fail(CircuitBreakerOpen)
                                  }
                       } yield result
                   }
        } yield result
    }

  sealed trait State

  object State {
    case object Closed   extends State
    case object HalfOpen extends State
    case object Open     extends State
  }

}
package nl.vroste.rezilience

import nl.vroste.rezilience.CircuitBreaker.State
import zio.duration._
import zio.{ Queue, Schedule, ZIO }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock

object CircuitBreakerSpec extends DefaultRunnableSpec {
  sealed trait Error
  case object CircuitBreakerClosedError extends Error
  case object MyCallError               extends Error

  def spec = suite("CircuitBreaker")(
    testM("lets successful calls through") {
      CircuitBreaker.make(10, Schedule.exponential(1.second)).use { cb =>
        for {
          _ <- cb.call(ZIO.unit).repeat(Schedule.recurs(20))
        } yield assertCompletes
      }
    },
    testM("fails fast after max nr failures calls") {
      CircuitBreaker.make(10, Schedule.exponential(1.second)).use { cb =>
        for {
          _      <- ZIO.foreach(1 to 10)(_ => cb.call(ZIO.fail(MyCallError)).either)
          result <- cb.call(ZIO.fail(MyCallError)).either
        } yield assert(result)(isLeft(equalTo(CircuitBreaker.CircuitBreakerOpen)))
      }
    },
    testM("reset to closed state after reset timeout") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb <- CircuitBreaker.make(
               10,
               Schedule.exponential(1.second),
               stateChanges.offer(_).ignore
             )
      } yield (stateChanges, cb)).use {
        case (stateChanges, cb) =>
          for {
            _ <- ZIO.foreach(1 to 10)(_ => cb.call(ZIO.fail(MyCallError)).either)
            _ <- stateChanges.take
            _ <- TestClock.adjust(3.second)
            _ <- stateChanges.take
            _ <- cb.call(ZIO.unit)
          } yield assertCompletes
      }
    },
    testM("retry exponentially") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb <- CircuitBreaker.make(
               3,
               Schedule.exponential(base = 1.second, factor = 2.0),
               stateChanges.offer(_).ignore
             )
      } yield (stateChanges, cb)).use {
        case (stateChanges, cb) =>
          for {
            _  <- ZIO.foreach(1 to 3)(_ => cb.call(ZIO.fail(MyCallError)).either)
            s1 <- stateChanges.take // Open
            _  <- TestClock.adjust(1.second)
            s2 <- stateChanges.take // HalfOpen
            _  <- cb.call(ZIO.fail(MyCallError)).either
            s3 <- stateChanges.take // Open again
            _  <- TestClock.adjust(1.second)
            s4 <- stateChanges.take.timeout(1.second)
            _  <- TestClock.adjust(1.second)
            s5 <- stateChanges.take
            _  <- cb.call(ZIO.unit)
            s6 <- stateChanges.take
          } yield assert(s1)(equalTo(CircuitBreaker.Open)) &&
            assert(s2)(equalTo(CircuitBreaker.HalfOpen)) &&
            assert(s3)(equalTo(CircuitBreaker.Open)) &&
            assert(s4)(isNone) &&
            assert(s5)(equalTo(CircuitBreaker.HalfOpen)) &&
            assert(s6)(equalTo(CircuitBreaker.Closed))
      }
    },
    testM("reset the exponential timeout after a Closed-Open-HalfOpen-Closed") {
      (for {
        stateChanges <- Queue.unbounded[State].toManaged_
        cb <- CircuitBreaker.make(
               3,
               Schedule.exponential(base = 1.second, factor = 2.0),
               stateChanges.offer(_).ignore
             )
      } yield (stateChanges, cb)).use {
        case (stateChanges, cb) =>
          for {
            _ <- ZIO.foreach(1 to 3)(_ => cb.call(ZIO.fail(MyCallError)).either)
            _ <- stateChanges.take // Open
            _ <- TestClock.adjust(1.second)
            _ <- stateChanges.take // HalfOpen

            _ <- cb.call(ZIO.fail(MyCallError)).either
            _ <- stateChanges.take // Open again, this time with double reset timeout

            _ <- TestClock.adjust(2.second)
            _ <- stateChanges.take // HalfOpen

            _ <- cb.call(ZIO.unit)
            _ <- stateChanges.take // Closed again

            _ <- ZIO.foreach(1 to 3)(_ => cb.call(ZIO.fail(MyCallError)).either)
            _ <- stateChanges.take // Open

            // Reset time should have re-initialized again
            _  <- TestClock.adjust(1.second)
            s1 <- stateChanges.take // HalfOpen
          } yield assert(s1)(equalTo(CircuitBreaker.HalfOpen))
      }
    }
  )
}

package nl.vroste.rezilience

import zio.clock.Clock
import zio.duration.{ durationInt, Duration }
import zio.stm.ZSTM
import zio.{ clock, Ref, URIO, ZIO, ZManaged }

trait RateLimiterPlatformSpecificObj {

  /**
   * Create a RateLimiter with metrics
   *
   * Metrics are emitted at a regular interval. When the RateLimiter is released, metrics for the final interval are
   * emitted.
   *
   * @param max
   * @param interval
   * @param onMetrics
   * @param metricsInterval
   * @param latencyHistogramSettings
   * @return
   */
  def makeWithMetrics[R1](
    inner: RateLimiter,
    onMetrics: RateLimiterMetrics => URIO[R1, Any],
    metricsInterval: Duration = 10.seconds,
    latencyHistogramSettings: HistogramSettings[Duration] = HistogramSettings(1.milli, 2.minutes)
  ): ZManaged[Clock with R1, Nothing, RateLimiter] = {

    def makeNewMetrics = clock.instant.flatMap(RateLimiterMetricsInternal.makeEmpty(_).commit)

    def collectMetrics(currentMetrics: RateLimiterMetricsInternal) =
      for {
        now         <- clock.instant
        userMetrics <- ZSTM.atomically {
                         for {
                           lastMetricsStart <- currentMetrics.start.get
                           interval          = java.time.Duration.between(lastMetricsStart, now)
                           userMetrics      <- currentMetrics.toUserMetrics(interval, latencyHistogramSettings)
                           _                <- currentMetrics.reset(now)
                         } yield userMetrics
                       }
        _           <- onMetrics(userMetrics)
      } yield ()

    for {
      metrics <- makeNewMetrics.toManaged_
      _       <- MetricsUtil.runCollectMetricsLoop(metricsInterval)(collectMetrics(metrics))
      env     <- ZManaged.environment[Clock]
    } yield new RateLimiter {
      override def apply[R, E, A](task: ZIO[R, E, A]): ZIO[R, E, A] = for {
        enqueueTime <- clock.instant.provide(env)
        // Keep track of whether the task was started to have correct statistics under interruption
        started     <- Ref.make(false)
        result      <- metrics.enqueueTask.commit
                         .toManaged(_ => metrics.taskInterrupted.commit.unlessM(started.get))
                         .use_ {
                           inner.apply {
                             for {
                               startTime <- clock.instant.provide(env)
                               latency    = java.time.Duration.between(enqueueTime, startTime)
                               _         <- metrics.taskStarted(latency).commit.ensuring(started.set(true))
                               result    <- task
                             } yield result
                           }
                         }
      } yield result
    }
  }
}

private[rezilience] object RateLimiterPlatformSpecificObj extends RateLimiterPlatformSpecificObj
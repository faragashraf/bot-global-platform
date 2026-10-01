import { CappedSignalRRetryPolicy, OPERATIONAL_FALLBACK_POLL_MILLIS } from './realtime'

describe('dashboard Realtime reconnect policy', () => {
  it('uses a five-second fallback while Realtime events remain authoritative', () => {
    expect(OPERATIONAL_FALLBACK_POLL_MILLIS).toBe(5_000)
  })

  it('retries indefinitely with a capped exponential delay', () => {
    const policy = new CappedSignalRRetryPolicy(() => 0.5)

    const delays = Array.from({ length: 8 }, (_, previousRetryCount) =>
      policy.nextRetryDelayInMilliseconds({
        elapsedMilliseconds: 0,
        previousRetryCount,
        retryReason: new Error('offline'),
      }),
    )

    expect(delays).toEqual([0, 1_000, 2_000, 5_000, 10_000, 10_000, 10_000, 10_000])
  })

  it('keeps jitter within twenty percent of the capped delay', () => {
    const low = new CappedSignalRRetryPolicy(() => 0)
    const high = new CappedSignalRRetryPolicy(() => 1)
    const context = {
      elapsedMilliseconds: 60_000,
      previousRetryCount: 20,
      retryReason: new Error('offline'),
    }

    expect(low.nextRetryDelayInMilliseconds(context)).toBe(8_000)
    expect(high.nextRetryDelayInMilliseconds(context)).toBe(12_000)
  })
})

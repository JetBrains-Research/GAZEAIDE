import { estimate } from "./core.mjs";

// Quality gates stay identical when collection time or the retry count changes.
export function targetQuality(samples, maxSpread) {
  const value = estimate(samples);
  const reasons = [];
  if (!value || value.retained < 10)
    reasons.push("need at least 10 stable samples");
  if (value && value.retained < Math.ceil(samples.length * 0.7))
    reasons.push("too many outliers");
  if (!value || value.spanMs < 700)
    reasons.push("need 700 ms of stable samples");
  if (value && value.spread90 > maxSpread)
    reasons.push("target estimates are too spread out");
  return {
    ok: !reasons.length,
    estimate: value,
    reasons,
    count: samples.length,
  };
}

// A failed target never advances or discards completed targets. Only explicit
// retry/cancel resolves the blocked state; no incomplete check can be saved.
export async function runGuideTargets({
  count,
  beforeTarget,
  collect,
  blocked,
  current,
}) {
  const trials = [];
  for (let index = 0; index < count; index++) {
    if (!current()) return null;
    await beforeTarget(index, trials);
    let attempt = 0;
    while (current()) {
      const result = await collect(index, attempt++);
      if (!current()) return null;
      if (result.trial) {
        trials.push(result.trial);
        break;
      }
      if (
        attempt % 3 === 0 &&
        !(await blocked({ index, attempt, trials, quality: result.quality }))
      )
        return null;
    }
    if (!current()) return null;
  }
  return trials;
}

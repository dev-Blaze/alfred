# Alfred 0.3.6 — local intent baseline and conversation fixes

- Bundled INT8 MiniLM-L6 encoder, tokenizer vocabulary and a trained four-class
  linear head. Inference runs on the webhook worker thread, reuses its session,
  and sends an advisory intentHint. Follow-ups, long inputs, low scores and errors
  fall back to uncertain. Ping does not load the model.
- This is a frozen MiniLM baseline trained on 64 synthetic examples, NOT a
  SetFit-fine-tuned or accuracy-calibrated production router. Four held-out smoke
  cases passed in Python and quantized ONNX. No Samsung/device latency claims.
- Existing capture-mode controls remain. n8n currently ignores intentHint; this
  release does not yet deliver the planned automatic unified capture experience.
- Convo waits up to 120 seconds instead of 20. Accepted replies with text and no
  clarification can be spoken with an explicit completion-unconfirmed warning;
  History retains accepted status. Failed/empty replies still stop the loop.
- Recipe destination instructions: Outline Fitness > Diet, resolving the actual
  hierarchy before writing; workouts remain in daily journals. No live recipe
  document was written during verification.
- 34 Android unit tests passed, including accepted-reply follow-up behavior.
  Release build and vital lint passed. Device inference, tokenizer parity across
  languages, battery and memory measurements remain unverified.
- Phone 0.3.6 / code 12; companion unchanged.

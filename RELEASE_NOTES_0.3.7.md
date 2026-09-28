# Alfred 0.3.7 — automatic capture and conversational replies

- Removed phone mode buttons; one voice flow selects task/note/convo request type
  from the local classifier, retaining dialogue for uncertain and mixed intents.
- Fixed ONNX native release crash caused by removed Java classes; added release
  instrumentation coverage. Phone SM_F976W passed five transcript classifications
  and follow-up/long-input fallback checks. Thresholds remain provisional and
  were adjusted using these smoke cases, not a calibrated held-out evaluation.
- Final device smoke run: 177 ms cold and about 1 ms warm for short examples.
  These few samples are not a percentile benchmark. Four examples overlap training.
- UI hierarchy confirmed no Task/Note/Convo buttons and one Start listening control.
- Published short, natural backend replies without report prefixes, Markdown,
  capability lists or template dumps. Tool-free replies complete the conversational
  turn; provider actions retain unconfirmed status until independently verified.
- Speech strips links and basic Markdown; History retains the original response.
  Unconfirmed tool actions still carry an explicit spoken uncertainty warning.
- Live greeting check 196 returned: “Hi! Yes, I'm here. What can I help you with?”
  with completed status. No documents modified for this check.
- Release unit tests, release build and vital lint passed. Updated APK installed
  over the existing app on the paired phone. Full microphone-to-provider testing
  and broad classifier accuracy testing remain outstanding. Companion unchanged.
- Phone version 0.3.7 / code 13.

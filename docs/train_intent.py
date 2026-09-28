"""Train a small MiniLM classification head and export a quantized ONNX encoder.

Run in a venv with sentence-transformers, scikit-learn, onnx and onnxruntime.
The seed corpus is synthetic; scores are not calibrated probabilities.
"""
import json
import sys
from pathlib import Path

import numpy as np
import torch
import onnxruntime as ort
from sentence_transformers import SentenceTransformer
from sklearn.linear_model import LogisticRegression
from onnxruntime.quantization import quantize_dynamic, QuantType

EXAMPLES = {
    "question": ["What is the weather today?", "How much protein is in an egg?", "Explain compound interest", "What meetings do I have tomorrow?", "How do I make pancakes?", "Who wrote this book?", "Is walking good exercise?", "What time is it in London?", "Tell me about the moon", "Why is the sky blue?", "Can you explain this recipe?", "How many grams in an ounce?", "What should I cook tonight?", "Do I have any free time Friday?", "What does this word mean?", "How long should I rest between sets?"],
    "action": ["Book a meeting tomorrow at noon", "Delete my appointment", "Move the meeting to Friday", "Save this recipe under Diet", "Create a contact for Jane", "Add milk to my shopping list", "Remind me to call mum", "Cancel my dentist appointment", "Update the recipe ingredients", "Find and open my journal", "Send an email to Alex", "Set a timer for ten minutes", "Schedule a workout next Tuesday", "Change the title of that note", "Create a new document", "Add this recipe to Outline"],
    "capture": ["Log my workout: three sets of ten squats", "Note that I felt tired today", "Journal entry: today was a good day", "I ate rice and chicken for lunch", "Record my weight as 80 kilograms", "I walked five kilometres this morning", "Remember this idea for later", "Here are my notes from the meeting", "Bench press three sets of eight at sixty kilos", "Write in today's journal that I went swimming", "I slept seven hours last night", "Capture this thought", "My workout lasted forty minutes", "I finished reading a book today", "Save a note: buy a birthday gift", "Daily journal: I met a friend for coffee"],
    "mixed": ["Save this recipe and tell me how much protein it has", "Book a meeting and tell me what else is scheduled", "Log my workout and suggest what to do tomorrow", "Add milk to the list and tell me if I need eggs", "Create a note and explain the topic", "Move my appointment and tell me the new time", "Record my lunch and estimate the calories", "Save these ingredients and suggest a dinner", "Schedule a run and explain how to warm up", "Update the recipe and tell me whether it serves four", "Log my weight and explain the change", "Write this in my journal and give me advice", "Save the article and summarize it", "Create the document and answer my question", "Add the event and list tomorrow's meetings", "Record the workout and calculate the total volume"],
}

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2", device="cpu")
texts = [t for examples in EXAMPLES.values() for t in examples]
labels = [label for label, examples in EXAMPLES.items() for _ in examples]
head = LogisticRegression(C=5, max_iter=1000).fit(model.encode(texts, normalize_embeddings=True), labels)
(out / "intent-head.json").write_text(json.dumps({"labels": head.classes_.tolist(), "weights": head.coef_.tolist(), "bias": head.intercept_.tolist(), "threshold": 0.70}))
model.tokenizer.save_pretrained(out)
encoder = model[0].auto_model.eval()
sample = model.tokenizer("What is on my calendar?", return_tensors="pt")
class Encoder(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder = encoder
    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.encoder(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids).last_hidden_state

torch.onnx.export(Encoder(), tuple(sample[k] for k in ["input_ids", "attention_mask", "token_type_ids"]), str(out / "encoder-fp32.onnx"), input_names=["input_ids", "attention_mask", "token_type_ids"], output_names=["embeddings"], dynamic_axes={k: {0: "batch", 1: "sequence"} for k in ["input_ids", "attention_mask", "token_type_ids", "embeddings"]}, opset_version=17, dynamo=False)
quantize_dynamic(str(out / "encoder-fp32.onnx"), str(out / "intent-encoder.onnx"), weight_type=QuantType.QInt8)
checks = ["What is the capital of France?", "Schedule a call with Sam", "I did twenty pushups today", "Save this soup recipe and tell me its calories"]
predictions = head.predict(model.encode(checks, normalize_embeddings=True)).tolist()
assert predictions == ["question", "action", "capture", "mixed"], predictions
session = ort.InferenceSession(str(out / "intent-encoder.onnx"), providers=["CPUExecutionProvider"])
for text, expected in zip(checks, predictions):
    tokens = model.tokenizer(text, return_tensors="np")
    vectors = session.run(None, dict(tokens))[0].mean(axis=1)
    vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
    assert head.predict(vectors)[0] == expected
assert np.isfinite(head.coef_).all()
print("Held-out smoke checks:", predictions)
print("Synthetic seed head only; device and representative accuracy evaluation required.")

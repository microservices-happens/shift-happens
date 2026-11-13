# Ollama latency in local Kubernetes

Measured on November 13, 2026, using Docker Desktop Kubernetes,
Ollama 0.12.3 and `llama3.2:1b`, with the model already downloaded.
The first generation loaded the model. The next five used the same running pod.

```sh
kubectl exec deployment/ollama -- ollama run llama3.2:1b --verbose \
  'Rank candidates 7 and 8 by fewest scheduled hours. Candidate 7 has 30 hours and candidate 8 has 12. Reply with the ordered IDs only.'
```

| Run | Ollama total duration | Model load duration |
| --- | ---: | ---: |
| First | 4.221 s | 2.288 s |
| 2 | 0.946 s | 0.063 s |
| 3 | 1.137 s | 0.067 s |
| 4 | 1.515 s | 0.067 s |
| 5 | 1.051 s | 0.067 s |
| 6 | 0.791 s | 0.070 s |

Median of the five warm runs was 1.051 s. These are Ollama's reported generation
durations for this prompt. The measurement excludes RabbitMQ and AI service processing.

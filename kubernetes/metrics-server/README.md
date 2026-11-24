# metrics-server

The HPA objects in `../autoscaling/hpa.yaml` need the Kubernetes metrics API. Docker Desktop does not
ship metrics-server, so install it once per cluster:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml
```

Docker Desktop's kubelet serves a self-signed certificate that metrics-server does not trust by default.
For the local cluster only, add the insecure-kubelet flag:

```bash
kubectl -n kube-system patch deployment metrics-server --type=json --patch '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
kubectl -n kube-system rollout status deployment/metrics-server
kubectl top nodes
kubectl top pods -n shift-happens
```

Production clusters should use a metrics-server installation whose CA trusts the kubelet certificate;
do not carry `--kubelet-insecure-tls` there. The HPA target utilisation is relative to each container's
`resources.requests.cpu`:

| Deployment | Min / max | CPU target | Reason |
|---|---|---|---|
| `scheduling-query` | 2 / 5 | 70% | Read side; keep two replicas for availability |
| `leave-query` | 2 / 5 | 70% | Read side; keep two replicas for availability |
| `frontend` | 2 / 5 | 60% | Stateless asset server |
| `notification-service` | KEDA `ScaledObject` | queue length 50 | Do **not** add an HPA as well |
| `email-function` | KEDA `ScaledJob` | queue length 20 | Jobs exit when the queue drains |

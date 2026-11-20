# Shift Happens wiki

How-tos for working on the Shift Happens microservices. The source of truth stays in the
repository: [`contracts/`](https://github.com/microservices-happens/shift-happens/blob/main/contracts/README.md),
[`docs/architecture.md`](https://github.com/microservices-happens/shift-happens/blob/main/docs/architecture.md)
and the per-service READMEs. These pages say how to do a task and point at those files. When a page and
the repository disagree, the repository wins: fix the page.

| Page | Use it when |
|---|---|
| [Local Kubernetes](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Local-Kubernetes.md) | You want the whole system running from the CI-built images on Docker Desktop Kubernetes or k3s in Colima |
| [Troubleshooting](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Troubleshooting.md) | Something that has gone wrong in this project before goes wrong again |
| [Adding a new event](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Adding-a-new-event.md) | You add or change an event between services |

The pages are kept in the repository under `docs/wiki/` and copied to the GitHub wiki, so a change to
them goes through a pull request like any other change. Links point at files on `main`, so they work in
both places.

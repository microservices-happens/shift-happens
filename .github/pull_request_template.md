## What and why

## Service(s) touched
- [ ] identity  - [ ] workforce  - [ ] scheduling  - [ ] leave  - [ ] ai  - [ ] notification  - [ ] audit  - [ ] email-function  - [ ] frontend  - [ ] infra/k8s

## Checklist
- [ ] Contract changes (`contracts/`) are reviewed by every affected service owner
- [ ] Unit tests for new business rules; integration/cooperation test if messaging changed
- [ ] DB change is a new Flyway migration (never edit an applied one)
- [ ] Service README / docs updated

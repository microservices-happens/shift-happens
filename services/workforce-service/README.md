# workforce-service

**Owns:** employees, employee contracts, employee job roles, departments, job roles and work locations.
**Database:** `workforce-db` (Postgres). **Compose:** `workforce-service`.

## Provides
- REST: [`contracts/openapi/workforce.yaml`](../../contracts/openapi/workforce.yaml)
  - Browser CRUD on `/employees`, `/employeecontracts`, `/employeejobroles`, `/departments`, `/jobroles` and `/worklocations`
  - Internal lookups for validation:
    - `GET /employees/{id}`, used by scheduling-command and leave-command
    - `GET /employeejobroles?employeeId=`, used by scheduling-command

## Publishes
| Event | When |
|---|---|
| `workforce.employee.created.v1` | Employee created |
| `workforce.employee.updated.v1` | Any employee change, including status |

Payload: [`employee.schema.json`](../../contracts/events/payloads/employee.schema.json). Use the transactional outbox.

## Consumes
Nothing.

## Extract from monolith
`employee`, `employeecontract`, `employeejobrole`, `department`, `jobrole` and `worklocation`. `EmployeeDto.loginPassword` is removed: passwords now live in identity-service.

## Done when
- [ ] Employee pages work through the gateway.
- [ ] Creating an employee produces an event that identity, scheduling-query, leave-query and audit all receive.

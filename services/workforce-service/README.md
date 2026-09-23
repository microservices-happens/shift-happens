# workforce-service

**Owns:** employees, employee contracts, employee job roles, departments, job roles and work locations.
**Database:** `workforce-db` (Postgres, Flyway). **Compose:** `workforce-service`.

## Provides
REST: [`contracts/openapi/workforce.yaml`](../../contracts/openapi/workforce.yaml). CRUD under `/v1/employees`, `/v1/employeecontracts`, `/v1/employeejobroles`, `/v1/departments`, `/v1/jobroles` and `/v1/worklocations`.

## Publishes (outbox)
| Event | When |
|---|---|
| `workforce.employee.created.v1` | Employee created |
| `workforce.employee.updated.v1` | Any employee field changes, or their job roles change (`jobRoleIds` is part of the snapshot) |
| `workforce.employee.deleted.v1` | Soft delete (tombstone) |

This is the only source of employee data for other services. Nobody calls Workforce synchronously.

## Consumes
Nothing.

## Extract from monolith
`employee`, `employeecontract`, `employeejobrole`, `department`, `jobrole` and `worklocation`. `EmployeeDto.loginPassword` is removed: passwords now live in identity-service.

## Done when
- [ ] Employee pages work through the gateway.
- [ ] Creating an employee reaches identity, scheduling, leave and audit (integration test on the outbox and RabbitMQ).

package dk.ek.shift_happens.workforce.api;

import dk.ek.shift_happens.workforce.api.dto.Department;
import dk.ek.shift_happens.workforce.api.dto.JobRole;
import dk.ek.shift_happens.workforce.api.dto.WorkLocation;
import dk.ek.shift_happens.workforce.application.IdempotencyService;
import dk.ek.shift_happens.workforce.infrastructure.persistence.ReferenceDataRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
class ReferenceDataController {

    private final ReferenceDataRepository referenceData;
    private final IdempotencyService idempotency;

    ReferenceDataController(ReferenceDataRepository referenceData, IdempotencyService idempotency) {
        this.referenceData = referenceData;
        this.idempotency = idempotency;
    }

    @GetMapping("/departments")
    List<Department> listDepartments() {
        return referenceData.listDepartments();
    }

    @PostMapping("/departments")
    ResponseEntity<?> createDepartment(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody Department request) {
        return idempotency.execute(key, "departments", request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(referenceData.createDepartment(request)));
    }

    @GetMapping("/departments/{id}")
    Department getDepartment(@PathVariable long id) {
        return referenceData.getDepartment(id);
    }

    @PatchMapping("/departments/{id}")
    Department updateDepartment(@PathVariable long id, @Valid @RequestBody Department request) {
        return referenceData.updateDepartment(id, request);
    }

    @DeleteMapping("/departments/{id}")
    ResponseEntity<Void> deleteDepartment(@PathVariable long id) {
        referenceData.deleteDepartment(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/jobroles")
    List<JobRole> listJobRoles() {
        return referenceData.listJobRoles();
    }

    @PostMapping("/jobroles")
    ResponseEntity<?> createJobRole(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody JobRole request) {
        return idempotency.execute(key, "jobroles", request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(referenceData.createJobRole(request)));
    }

    @GetMapping("/jobroles/{id}")
    JobRole getJobRole(@PathVariable long id) {
        return referenceData.getJobRole(id);
    }

    @PutMapping("/jobroles/{id}")
    JobRole updateJobRole(@PathVariable long id, @Valid @RequestBody JobRole request) {
        return referenceData.updateJobRole(id, request);
    }

    @DeleteMapping("/jobroles/{id}")
    ResponseEntity<Void> deleteJobRole(@PathVariable long id) {
        referenceData.deleteJobRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/worklocations")
    List<WorkLocation> listWorkLocations() {
        return referenceData.listWorkLocations();
    }

    @PostMapping("/worklocations")
    ResponseEntity<?> createWorkLocation(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody WorkLocation request) {
        return idempotency.execute(key, "worklocations", request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(referenceData.createWorkLocation(request)));
    }

    @GetMapping("/worklocations/{id}")
    WorkLocation getWorkLocation(@PathVariable long id) {
        return referenceData.getWorkLocation(id);
    }

    @PutMapping("/worklocations/{id}")
    WorkLocation updateWorkLocation(@PathVariable long id, @Valid @RequestBody WorkLocation request) {
        return referenceData.updateWorkLocation(id, request);
    }

    @DeleteMapping("/worklocations/{id}")
    ResponseEntity<Void> deleteWorkLocation(@PathVariable long id) {
        referenceData.deleteWorkLocation(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource is referenced by another Workforce record");
    }
}

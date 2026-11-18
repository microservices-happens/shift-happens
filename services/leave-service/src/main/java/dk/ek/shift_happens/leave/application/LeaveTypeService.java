package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveType;
import dk.ek.shift_happens.leave.domain.LeaveTypeConflictException;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leave types: plain CRUD. The event catalog defines no leave-type events, so nothing is published.
 *
 * <p>Delete is hard: with no tombstone event and no consumer of leave types, a physical delete is the
 * simplest thing that keeps integrity. A type that any request references can never be deleted (409, and
 * the foreign key backs that up), so no request is left pointing at a missing type.
 */
@Service
@Profile("command")
public class LeaveTypeService {

    private final LeaveTypeRepository types;
    private final LeaveRequestRepository requests;

    LeaveTypeService(LeaveTypeRepository types, LeaveRequestRepository requests) {
        this.types = types;
        this.requests = requests;
    }

    @Transactional
    public LeaveType create(LeaveType leaveType) {
        requireFreeName(leaveType.leaveTypeName(), null);
        return types.save(leaveType);
    }

    @Transactional
    public LeaveType replace(long leaveTypeId, LeaveType leaveType) {
        types.findById(leaveTypeId).orElseThrow(() -> new LeaveTypeNotFoundException(leaveTypeId));
        requireFreeName(leaveType.leaveTypeName(), leaveTypeId);
        return types.save(leaveType.withId(leaveTypeId));
    }

    @Transactional
    public void delete(long leaveTypeId) {
        types.findById(leaveTypeId).orElseThrow(() -> new LeaveTypeNotFoundException(leaveTypeId));
        if (requests.existsByLeaveTypeId(leaveTypeId)) {
            throw new LeaveTypeConflictException("leave type " + leaveTypeId + " is used by leave requests");
        }
        types.delete(leaveTypeId);
    }

    private void requireFreeName(String name, Long ownId) {
        if (types.existsByNameExcluding(name, ownId)) {
            throw new LeaveTypeConflictException("a leave type named '" + name + "' already exists");
        }
    }
}

package com.heysaz.erp.hr.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.heysaz.erp.hr.api.HrService;
import com.heysaz.erp.platform.audit.AuditService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;

@Service
class HrServiceImpl implements HrService {

    private final HrRepository repository;
    private final AuditService audit;

    HrServiceImpl(HrRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    // ------------------------------------------------------------- Employees

    @Override
    @Transactional
    public EmployeeView createEmployee(CreateEmployeeCommand command) {
        if (command.userId() != null) {
            requireUserUnlinked(command.userId(), null);
        }
        UUID id = UUID.randomUUID();
        repository.insertEmployee(id, TenantContext.requireOrgId(), command.code(), command.fullName(),
                command.title(), command.employmentType(), command.phone(), command.email(),
                command.userId(), command.hiredOn());
        EmployeeView view = repository.findEmployee(id).orElseThrow();
        audit.record("hr.employee_created", "Employee", id.toString(), null, view,
                AuditService.Outcome.SUCCESS);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeView getEmployee(UUID employeeId) {
        return repository.findEmployee(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeView> listEmployees(boolean includeInactive) {
        return repository.listEmployees(includeInactive);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmployeeView> getEmployeeByUser(UUID userId) {
        return repository.findEmployeeByUser(userId);
    }

    @Override
    @Transactional
    public EmployeeView linkUser(UUID employeeId, UUID userId) {
        getEmployee(employeeId);
        requireUserUnlinked(userId, employeeId);
        repository.updateUserLink(employeeId, userId);
        return repository.findEmployee(employeeId).orElseThrow();
    }

    @Override
    @Transactional
    public void deactivateEmployee(UUID employeeId) {
        getEmployee(employeeId);
        repository.deactivateEmployee(employeeId);
    }

    private void requireUserUnlinked(UUID userId, UUID exceptEmployeeId) {
        repository.findEmployeeByUser(userId).ifPresent(existing -> {
            if (!existing.id().equals(exceptEmployeeId)) {
                throw ApiException.conflict("That user is already linked to another employee");
            }
        });
    }

    // ------------------------------------------------------------ Attendance

    @Override
    @Transactional
    public AttendanceView checkIn(UUID employeeId) {
        requireActiveEmployee(employeeId);
        LocalDate today = LocalDate.now();
        if (repository.findOpenSelfAttendance(employeeId, today).isPresent()) {
            throw ApiException.conflict("This employee is already checked in and has not checked out");
        }
        UUID id = UUID.randomUUID();
        repository.insertAttendance(id, TenantContext.requireOrgId(), employeeId, today,
                Instant.now(), null, "SELF", "RECORDED", null);
        return repository.findAttendance(id).orElseThrow();
    }

    @Override
    @Transactional
    public AttendanceView checkOut(UUID employeeId) {
        requireActiveEmployee(employeeId);
        AttendanceView open = repository.findOpenSelfAttendance(employeeId, LocalDate.now())
                .orElseThrow(() -> ApiException.conflict("This employee has no open check-in to close"));
        repository.setCheckOut(open.id(), Instant.now());
        return repository.findAttendance(open.id()).orElseThrow();
    }

    @Override
    @Transactional
    public AttendanceView recordManualAttendance(ManualAttendanceCommand command) {
        requireActiveEmployee(command.employeeId());
        UUID id = UUID.randomUUID();
        // FR-HR-002: an after-the-fact entry is held PENDING until it is approved.
        repository.insertAttendance(id, TenantContext.requireOrgId(), command.employeeId(),
                command.workDate(), command.checkInAt(), command.checkOutAt(), "MANUAL", "PENDING",
                command.notes());
        return repository.findAttendance(id).orElseThrow();
    }

    @Override
    @Transactional
    public AttendanceView approveAttendance(UUID attendanceId, boolean approved) {
        AttendanceView attendance = repository.findAttendance(attendanceId)
                .orElseThrow(() -> ApiException.notFound("Attendance record"));
        if (!"PENDING".equals(attendance.status())) {
            throw ApiException.conflict("Only a PENDING attendance entry can be decided");
        }
        repository.setAttendanceStatus(attendanceId, approved ? "APPROVED" : "REJECTED", actorUserId());
        return repository.findAttendance(attendanceId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceView> listAttendance(UUID employeeId, LocalDate from, LocalDate to) {
        getEmployee(employeeId);
        LocalDate f = from == null ? LocalDate.now().minusMonths(1) : from;
        LocalDate t = to == null ? LocalDate.now() : to;
        return repository.listAttendance(employeeId, f, t);
    }

    // ----------------------------------------------------------------- Leave

    @Override
    @Transactional
    public LeaveRequestView requestLeave(RequestLeaveCommand command) {
        requireActiveEmployee(command.employeeId());
        if (command.endDate().isBefore(command.startDate())) {
            throw ApiException.conflict("Leave end date is before the start date");
        }
        BigDecimal days = BigDecimal.valueOf(
                ChronoUnit.DAYS.between(command.startDate(), command.endDate()) + 1);
        UUID id = UUID.randomUUID();
        repository.insertLeave(id, TenantContext.requireOrgId(), command.employeeId(),
                command.leaveType(), command.startDate(), command.endDate(), days,
                command.reason(), "REQUESTED");
        return repository.findLeave(id).orElseThrow();
    }

    @Override
    @Transactional
    public LeaveRequestView decideLeave(UUID leaveRequestId, boolean approve, String note) {
        LeaveRequestView request = repository.findLeave(leaveRequestId)
                .orElseThrow(() -> ApiException.notFound("Leave request"));
        if (!"REQUESTED".equals(request.status())) {
            throw ApiException.conflict("This leave request has already been " + request.status().toLowerCase());
        }
        if (approve) {
            BigDecimal remaining = remainingBalance(request.employeeId(), request.leaveType());
            if (request.days().compareTo(remaining) > 0) {
                throw ApiException.conflict("Insufficient " + request.leaveType()
                        + " balance: " + remaining + " day(s) remain, " + request.days() + " requested");
            }
        }
        repository.setLeaveDecision(leaveRequestId, approve ? "APPROVED" : "REJECTED", actorUserId());
        LeaveRequestView decided = repository.findLeave(leaveRequestId).orElseThrow();
        audit.record("hr.leave_" + (approve ? "approved" : "rejected"), "LeaveRequest",
                leaveRequestId.toString(), request, decided, AuditService.Outcome.SUCCESS);
        return decided;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveRequestView> listLeave(UUID employeeId, String status) {
        getEmployee(employeeId);
        return repository.listLeave(employeeId, status);
    }

    @Override
    @Transactional
    public void setLeaveEntitlement(UUID employeeId, String leaveType, BigDecimal entitledDays) {
        getEmployee(employeeId);
        if (entitledDays == null || entitledDays.signum() < 0) {
            throw ApiException.conflict("Entitlement days must not be negative");
        }
        repository.upsertEntitlement(TenantContext.requireOrgId(), employeeId, leaveType, entitledDays);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveBalanceView> leaveBalance(UUID employeeId) {
        getEmployee(employeeId);
        List<LeaveBalanceView> balances = new ArrayList<>();
        for (HrRepository.Entitlement e : repository.listEntitlements(employeeId)) {
            BigDecimal used = repository.approvedDays(employeeId, e.leaveType());
            balances.add(new LeaveBalanceView(e.leaveType(), e.entitledDays(), used,
                    e.entitledDays().subtract(used)));
        }
        return balances;
    }

    private BigDecimal remainingBalance(UUID employeeId, String leaveType) {
        BigDecimal entitled = repository.listEntitlements(employeeId).stream()
                .filter(e -> e.leaveType().equals(leaveType))
                .map(HrRepository.Entitlement::entitledDays)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        return entitled.subtract(repository.approvedDays(employeeId, leaveType));
    }

    private void requireActiveEmployee(UUID employeeId) {
        EmployeeView employee = getEmployee(employeeId);
        if (!employee.active()) {
            throw ApiException.conflict("Employee " + employee.code() + " is not active");
        }
    }

    private UUID actorUserId() {
        return TenantContext.principal().map(Principal::userId).orElse(null);
    }
}

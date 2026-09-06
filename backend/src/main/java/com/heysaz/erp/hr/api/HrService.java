package com.heysaz.erp.hr.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Human resources: employees, attendance and leave (Section 11, FR-HR-001..003, 006).
 *
 * <p>An employee may be linked to exactly one user account, and that link is what ties till
 * and shift activity back to a person (FR-HR-006). Live check-in/out is recorded as-is;
 * after-the-fact manual attendance is held for approval. Leave is requested, then approved or
 * rejected, and a running balance is tracked against a per-type entitlement.
 */
public interface HrService {

    // ------------------------------------------------------------- Employees

    record CreateEmployeeCommand(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 200) String fullName,
            String title,
            String employmentType,
            String phone,
            String email,
            /** Optional login this employee uses; must not already belong to another employee. */
            UUID userId,
            LocalDate hiredOn) {
    }

    record EmployeeView(
            UUID id, String code, String fullName, String title, String employmentType,
            String phone, String email, UUID userId, LocalDate hiredOn, boolean active) {
    }

    EmployeeView createEmployee(CreateEmployeeCommand command);

    EmployeeView getEmployee(UUID employeeId);

    List<EmployeeView> listEmployees(boolean includeInactive);

    /** FR-HR-006: the employee behind a login, if any. */
    Optional<EmployeeView> getEmployeeByUser(UUID userId);

    EmployeeView linkUser(UUID employeeId, UUID userId);

    void deactivateEmployee(UUID employeeId);

    // ------------------------------------------------------------ Attendance

    record AttendanceView(
            UUID id, UUID employeeId, LocalDate workDate, Instant checkInAt, Instant checkOutAt,
            String source, String status, UUID approvedBy, String notes) {
    }

    record ManualAttendanceCommand(
            @NotNull UUID employeeId,
            @NotNull LocalDate workDate,
            Instant checkInAt,
            Instant checkOutAt,
            String notes) {
    }

    AttendanceView checkIn(UUID employeeId);

    AttendanceView checkOut(UUID employeeId);

    AttendanceView recordManualAttendance(ManualAttendanceCommand command);

    AttendanceView approveAttendance(UUID attendanceId, boolean approved);

    List<AttendanceView> listAttendance(UUID employeeId, LocalDate from, LocalDate to);

    // ----------------------------------------------------------------- Leave

    record RequestLeaveCommand(
            @NotNull UUID employeeId,
            @NotBlank String leaveType,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            String reason) {
    }

    record LeaveRequestView(
            UUID id, UUID employeeId, String leaveType, LocalDate startDate, LocalDate endDate,
            BigDecimal days, String reason, String status, UUID decidedBy, Instant decidedAt) {
    }

    record LeaveBalanceView(String leaveType, BigDecimal entitledDays, BigDecimal usedDays,
                            BigDecimal remainingDays) {
    }

    LeaveRequestView requestLeave(RequestLeaveCommand command);

    /** Approves or rejects a pending request; approval is checked against the balance. */
    LeaveRequestView decideLeave(UUID leaveRequestId, boolean approve, String note);

    List<LeaveRequestView> listLeave(UUID employeeId, String status);

    void setLeaveEntitlement(UUID employeeId, String leaveType, BigDecimal entitledDays);

    List<LeaveBalanceView> leaveBalance(UUID employeeId);
}

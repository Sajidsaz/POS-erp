package com.heysaz.erp.hr.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.heysaz.erp.hr.api.HrService.AttendanceView;
import com.heysaz.erp.hr.api.HrService.EmployeeView;
import com.heysaz.erp.hr.api.HrService.LeaveRequestView;

@Repository
class HrRepository {

    private final JdbcClient jdbc;

    HrRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------- Employees

    void insertEmployee(UUID id, UUID orgId, String code, String fullName, String title,
                        String employmentType, String phone, String email, UUID userId,
                        LocalDate hiredOn) {
        jdbc.sql("""
                INSERT INTO app.employee
                    (id, org_id, code, full_name, title, employment_type, phone, email, user_id, hired_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(code).param(fullName).param(title)
                .param(employmentType).param(phone).param(email).param(userId).param(hiredOn)
                .update();
    }

    Optional<EmployeeView> findEmployee(UUID id) {
        return jdbc.sql(EMPLOYEE_SELECT + " WHERE id = ?").param(id)
                .query(this::mapEmployee).optional();
    }

    Optional<EmployeeView> findEmployeeByUser(UUID userId) {
        return jdbc.sql(EMPLOYEE_SELECT + " WHERE user_id = ?").param(userId)
                .query(this::mapEmployee).optional();
    }

    List<EmployeeView> listEmployees(boolean includeInactive) {
        String filter = includeInactive ? "" : " WHERE active";
        return jdbc.sql(EMPLOYEE_SELECT + filter + " ORDER BY full_name")
                .query(this::mapEmployee).list();
    }

    void updateUserLink(UUID employeeId, UUID userId) {
        jdbc.sql("UPDATE app.employee SET user_id = ?, updated_at = now() WHERE id = ?")
                .param(userId).param(employeeId).update();
    }

    void deactivateEmployee(UUID employeeId) {
        jdbc.sql("UPDATE app.employee SET active = false, updated_at = now() WHERE id = ?")
                .param(employeeId).update();
    }

    private static final String EMPLOYEE_SELECT = """
            SELECT id, code, full_name, title, employment_type, phone, email, user_id, hired_on, active
            FROM app.employee""";

    private EmployeeView mapEmployee(ResultSet rs, int rowNum) throws SQLException {
        return new EmployeeView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("full_name"),
                rs.getString("title"),
                rs.getString("employment_type"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getObject("user_id", UUID.class),
                rs.getObject("hired_on", LocalDate.class),
                rs.getBoolean("active"));
    }

    // ------------------------------------------------------------ Attendance

    void insertAttendance(UUID id, UUID orgId, UUID employeeId, LocalDate workDate,
                          Instant checkInAt, Instant checkOutAt, String source, String status,
                          String notes) {
        jdbc.sql("""
                INSERT INTO app.attendance
                    (id, org_id, employee_id, work_date, check_in_at, check_out_at, source, status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(employeeId).param(workDate)
                .param(checkInAt == null ? null : java.sql.Timestamp.from(checkInAt))
                .param(checkOutAt == null ? null : java.sql.Timestamp.from(checkOutAt))
                .param(source).param(status).param(notes)
                .update();
    }

    Optional<AttendanceView> findAttendance(UUID id) {
        return jdbc.sql(ATTENDANCE_SELECT + " WHERE id = ?").param(id)
                .query(this::mapAttendance).optional();
    }

    /** The still-open live check-in for an employee on a date, if any. */
    Optional<AttendanceView> findOpenSelfAttendance(UUID employeeId, LocalDate workDate) {
        return jdbc.sql(ATTENDANCE_SELECT + """
                 WHERE employee_id = ? AND work_date = ? AND source = 'SELF'
                   AND check_out_at IS NULL
                 ORDER BY check_in_at DESC LIMIT 1
                """)
                .param(employeeId).param(workDate)
                .query(this::mapAttendance).optional();
    }

    void setCheckOut(UUID id, Instant checkOutAt) {
        jdbc.sql("UPDATE app.attendance SET check_out_at = ? WHERE id = ?")
                .param(java.sql.Timestamp.from(checkOutAt)).param(id).update();
    }

    void setAttendanceStatus(UUID id, String status, UUID approvedBy) {
        jdbc.sql("UPDATE app.attendance SET status = ?, approved_by = ? WHERE id = ?")
                .param(status).param(approvedBy).param(id).update();
    }

    List<AttendanceView> listAttendance(UUID employeeId, LocalDate from, LocalDate to) {
        return jdbc.sql(ATTENDANCE_SELECT + """
                 WHERE employee_id = ? AND work_date BETWEEN ? AND ?
                 ORDER BY work_date DESC, check_in_at DESC
                """)
                .param(employeeId).param(from).param(to)
                .query(this::mapAttendance).list();
    }

    private static final String ATTENDANCE_SELECT = """
            SELECT id, employee_id, work_date, check_in_at, check_out_at, source, status,
                   approved_by, notes
            FROM app.attendance""";

    private AttendanceView mapAttendance(ResultSet rs, int rowNum) throws SQLException {
        return new AttendanceView(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getTimestamp("check_in_at") == null ? null : rs.getTimestamp("check_in_at").toInstant(),
                rs.getTimestamp("check_out_at") == null ? null : rs.getTimestamp("check_out_at").toInstant(),
                rs.getString("source"),
                rs.getString("status"),
                rs.getObject("approved_by", UUID.class),
                rs.getString("notes"));
    }

    // ----------------------------------------------------------------- Leave

    void insertLeave(UUID id, UUID orgId, UUID employeeId, String leaveType, LocalDate start,
                     LocalDate end, BigDecimal days, String reason, String status) {
        jdbc.sql("""
                INSERT INTO app.leave_request
                    (id, org_id, employee_id, leave_type, start_date, end_date, days, reason, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(id).param(orgId).param(employeeId).param(leaveType).param(start).param(end)
                .param(days).param(reason).param(status)
                .update();
    }

    Optional<LeaveRequestView> findLeave(UUID id) {
        return jdbc.sql(LEAVE_SELECT + " WHERE id = ?").param(id)
                .query(this::mapLeave).optional();
    }

    void setLeaveDecision(UUID id, String status, UUID decidedBy) {
        jdbc.sql("""
                UPDATE app.leave_request SET status = ?, decided_by = ?, decided_at = now()
                WHERE id = ?
                """)
                .param(status).param(decidedBy).param(id).update();
    }

    List<LeaveRequestView> listLeave(UUID employeeId, String status) {
        String filter = status == null ? "" : " AND status = ?";
        var spec = jdbc.sql(LEAVE_SELECT + " WHERE employee_id = ?" + filter
                + " ORDER BY start_date DESC").param(employeeId);
        if (status != null) {
            spec = spec.param(status);
        }
        return spec.query(this::mapLeave).list();
    }

    /** Days already committed to approved leave of a type — the "used" side of the balance. */
    BigDecimal approvedDays(UUID employeeId, String leaveType) {
        return jdbc.sql("""
                SELECT coalesce(sum(days), 0) FROM app.leave_request
                WHERE employee_id = ? AND leave_type = ? AND status = 'APPROVED'
                """)
                .param(employeeId).param(leaveType)
                .query(BigDecimal.class).single();
    }

    private static final String LEAVE_SELECT = """
            SELECT id, employee_id, leave_type, start_date, end_date, days, reason, status,
                   decided_by, decided_at
            FROM app.leave_request""";

    private LeaveRequestView mapLeave(ResultSet rs, int rowNum) throws SQLException {
        return new LeaveRequestView(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("leave_type"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("days"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getObject("decided_by", UUID.class),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant());
    }

    // ----------------------------------------------------------- Entitlement

    void upsertEntitlement(UUID orgId, UUID employeeId, String leaveType, BigDecimal entitledDays) {
        jdbc.sql("""
                INSERT INTO app.leave_entitlement (id, org_id, employee_id, leave_type, entitled_days)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (org_id, employee_id, leave_type)
                DO UPDATE SET entitled_days = EXCLUDED.entitled_days
                """)
                .param(UUID.randomUUID()).param(orgId).param(employeeId).param(leaveType).param(entitledDays)
                .update();
    }

    record Entitlement(String leaveType, BigDecimal entitledDays) {
    }

    List<Entitlement> listEntitlements(UUID employeeId) {
        return jdbc.sql("""
                SELECT leave_type, entitled_days FROM app.leave_entitlement
                WHERE employee_id = ? ORDER BY leave_type
                """)
                .param(employeeId)
                .query((rs, n) -> new Entitlement(rs.getString("leave_type"), rs.getBigDecimal("entitled_days")))
                .list();
    }
}

package com.heysaz.erp.hr.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.hr.api.HrService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/hr")
class HrController {

    private final HrService hrService;

    HrController(HrService hrService) {
        this.hrService = hrService;
    }

    record DecisionRequest(boolean approve, String note) {
    }

    record EntitlementRequest(@NotBlank String leaveType, @NotNull BigDecimal entitledDays) {
    }

    // ------------------------------------------------------------- Employees

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.EmployeeView createEmployee(@Valid @RequestBody HrService.CreateEmployeeCommand command) {
        return hrService.createEmployee(command);
    }

    @GetMapping("/employees")
    @PreAuthorize("hasAuthority('employees.read')")
    List<HrService.EmployeeView> listEmployees(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return hrService.listEmployees(includeInactive);
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("hasAuthority('employees.read')")
    HrService.EmployeeView getEmployee(@PathVariable UUID employeeId) {
        return hrService.getEmployee(employeeId);
    }

    @PostMapping("/employees/{employeeId}/link-user")
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.EmployeeView linkUser(@PathVariable UUID employeeId, @RequestParam UUID userId) {
        return hrService.linkUser(employeeId, userId);
    }

    @PostMapping("/employees/{employeeId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('employees.write')")
    void deactivate(@PathVariable UUID employeeId) {
        hrService.deactivateEmployee(employeeId);
    }

    // ------------------------------------------------------------ Attendance

    @PostMapping("/employees/{employeeId}/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.AttendanceView checkIn(@PathVariable UUID employeeId) {
        return hrService.checkIn(employeeId);
    }

    @PostMapping("/employees/{employeeId}/check-out")
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.AttendanceView checkOut(@PathVariable UUID employeeId) {
        return hrService.checkOut(employeeId);
    }

    @PostMapping("/attendance/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.AttendanceView recordManual(@Valid @RequestBody HrService.ManualAttendanceCommand command) {
        return hrService.recordManualAttendance(command);
    }

    @PostMapping("/attendance/{attendanceId}/decide")
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.AttendanceView decideAttendance(
            @PathVariable UUID attendanceId, @RequestParam boolean approved) {
        return hrService.approveAttendance(attendanceId, approved);
    }

    @GetMapping("/employees/{employeeId}/attendance")
    @PreAuthorize("hasAuthority('employees.read')")
    List<HrService.AttendanceView> listAttendance(
            @PathVariable UUID employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return hrService.listAttendance(employeeId, from, to);
    }

    // ----------------------------------------------------------------- Leave

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.LeaveRequestView requestLeave(@Valid @RequestBody HrService.RequestLeaveCommand command) {
        return hrService.requestLeave(command);
    }

    @PostMapping("/leave/{leaveRequestId}/decide")
    @PreAuthorize("hasAuthority('employees.write')")
    HrService.LeaveRequestView decideLeave(
            @PathVariable UUID leaveRequestId, @RequestBody DecisionRequest request) {
        return hrService.decideLeave(leaveRequestId, request.approve(), request.note());
    }

    @GetMapping("/employees/{employeeId}/leave")
    @PreAuthorize("hasAuthority('employees.read')")
    List<HrService.LeaveRequestView> listLeave(
            @PathVariable UUID employeeId, @RequestParam(required = false) String status) {
        return hrService.listLeave(employeeId, status);
    }

    @PutMapping("/employees/{employeeId}/entitlement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('employees.write')")
    void setEntitlement(@PathVariable UUID employeeId, @Valid @RequestBody EntitlementRequest request) {
        hrService.setLeaveEntitlement(employeeId, request.leaveType(), request.entitledDays());
    }

    @GetMapping("/employees/{employeeId}/leave-balance")
    @PreAuthorize("hasAuthority('employees.read')")
    List<HrService.LeaveBalanceView> leaveBalance(@PathVariable UUID employeeId) {
        return hrService.leaveBalance(employeeId);
    }
}

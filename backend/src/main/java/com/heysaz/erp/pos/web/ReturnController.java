package com.heysaz.erp.pos.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.heysaz.erp.pos.api.ReturnService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/pos")
class ReturnController {

    private final ReturnService returnService;

    ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('returns.write', 'returns.limited')")
    ReturnService.SaleReturnView processReturn(
            @Valid @RequestBody ReturnService.ProcessReturnCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return returnService.processReturn(command, idempotencyKey);
    }

    @PostMapping("/exchanges")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('returns.write', 'returns.limited') and hasAuthority('pos.sales.write')")
    ReturnService.ExchangeResultView processExchange(
            @Valid @RequestBody ReturnService.ProcessExchangeCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return returnService.processExchange(command, idempotencyKey);
    }

    @GetMapping("/returns/{returnId}")
    @PreAuthorize("hasAuthority('returns.read')")
    ReturnService.SaleReturnView getReturn(@PathVariable UUID returnId) {
        return returnService.getReturn(returnId);
    }

    @GetMapping("/returns")
    @PreAuthorize("hasAuthority('returns.read')")
    List<ReturnService.SaleReturnView> listReturns(@RequestParam UUID shopId) {
        return returnService.listReturns(shopId);
    }
}

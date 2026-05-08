package com.edu.muc.app.modules.llmprovider.controller;

import com.edu.muc.app.common.Result;
import com.edu.muc.app.modules.llmprovider.dto.CreateProviderRequest;
import com.edu.muc.app.modules.llmprovider.dto.DefaultProviderDTO;
import com.edu.muc.app.modules.llmprovider.dto.ProviderDTO;
import com.edu.muc.app.modules.llmprovider.dto.ProviderTestResult;
import com.edu.muc.app.modules.llmprovider.dto.UpdateProviderRequest;
import com.edu.muc.app.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/llm-provider")
@RequiredArgsConstructor
@Slf4j
public class LlmProviderController {

    private final LlmProviderConfigService configService;

    @GetMapping("/list")
    public Result<List<ProviderDTO>> listProviders() {
        return Result.success(configService.listProviders());
    }

    @GetMapping("/{id}")
    public Result<ProviderDTO> getProvider(@PathVariable String id) {
        return Result.success(configService.getProvider(id));
    }

    @PostMapping
    public Result<Void> createProvider(@RequestBody @Valid CreateProviderRequest request) {
        configService.createProvider(request);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> updateProvider(@PathVariable String id,
        @RequestBody UpdateProviderRequest request) {
        configService.updateProvider(id, request);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProvider(@PathVariable String id) {
        configService.deleteProvider(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<ProviderTestResult> testProvider(@PathVariable String id) {
        return Result.success(configService.testProvider(id));
    }

    @PostMapping("/reload")
    public Result<Void> reloadProviders() {
        configService.reloadProviders();
        return Result.success();
    }

    @GetMapping("/default-provider")
    public Result<DefaultProviderDTO> getDefaultProvider() {
        return Result.success(configService.getDefaultProvider());
    }

    @PutMapping("/default-provider")
    public Result<Void> updateDefaultProvider(@RequestBody DefaultProviderDTO request) {
        configService.updateDefaultProvider(request);
        return Result.success();
    }

    @PutMapping("/default-embedding-provider")
    public Result<Void> updateDefaultEmbeddingProvider(@RequestBody DefaultProviderDTO request) {
        configService.updateDefaultEmbeddingProvider(request);
        return Result.success();
    }
}

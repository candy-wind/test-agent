package vip.mate.agent.platform.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.agent.platform.common.ApiResponse;
import vip.mate.agent.platform.user.dto.CreateUserRequest;
import vip.mate.agent.platform.user.dto.ResetPasswordRequest;
import vip.mate.agent.platform.user.dto.UpdateUserRequest;
import vip.mate.agent.platform.user.dto.UserView;

import java.util.List;

/** 账号管理接口：登录校验由 AuthFilter 负责，管理员权限由 UserService 校验。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformUserController {

    private final UserService userService;

    @GetMapping("/users")
    public ApiResponse<List<UserView>> list() {
        return ApiResponse.ok(userService.list());
    }

    @PostMapping("/users")
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserRequest request) {
        log.info("[users] 创建账号 username={} role={}", request.username(), request.role());
        return ApiResponse.ok(userService.create(request));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<UserView> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateUserRequest request) {
        log.info("[users] 更新账号 id={}", id);
        return ApiResponse.ok(userService.update(id, request));
    }

    @PutMapping("/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        log.info("[users] 重置口令 id={}", id);
        userService.resetPassword(id, request);
        return ApiResponse.ok(null);
    }
}

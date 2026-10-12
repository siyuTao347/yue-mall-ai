package com.example.agent.customer;

import api.agent.AgentActor;
import api.response.ApiResponse;
import api.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.agent.customer.CustomerJson.*;

@RestController
public class CustomerController {
    private final CustomerStore store;private final CustomerRunner runner;private final IntentReleaseService releases;
    public CustomerController(CustomerStore store,CustomerRunner runner,IntentReleaseService releases){this.store=store;this.runner=runner;this.releases=releases;}
    public record Send(@NotBlank @Size(max=2000) String content,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String clientMessageId){}
    public record Feedback(@Min(1) @Max(5) int rating,@Size(max=512) String comment){}
    public record Resolution(@NotBlank @Size(max=512) String resolution){}
    public record ReleaseRequest(@NotNull @Size(min=3,max=200) List<IntentReleaseService.Evaluation> evaluations){}
    static AgentActor actor(HttpServletRequest request){String token=request.getHeader("Authorization");Long id=JwtUtil.parseUserId(token);String role=JwtUtil.parseRole(token);
        if(id==null||id<=0)throw error(401,"AGENT_UNAUTHENTICATED","请先登录");
        String header=request.getHeader("X-User-Id");if(header!=null&&!header.equals(id.toString()))throw error(403,"AGENT_PERMISSION_DENIED","身份不一致");return new AgentActor(id,role);}
    private static AgentActor admin(HttpServletRequest request){AgentActor actor=actor(request);if(!"ADMIN".equalsIgnoreCase(actor.role()))throw error(403,"AGENT_PERMISSION_DENIED","仅管理员可访问");return actor;}
    @PostMapping("/api/agent/sessions") public Object create(HttpServletRequest r){return ApiResponse.success(store.create(actor(r)));}
    @GetMapping("/api/agent/sessions/{session}") public Object session(HttpServletRequest r,@PathVariable String session){return ApiResponse.success(store.session(actor(r),session));}
    @PostMapping("/api/agent/sessions/{session}/messages") public Object send(HttpServletRequest r,@PathVariable String session,@Valid @RequestBody Send body){return ApiResponse.success(runner.send(actor(r),session,body.clientMessageId(),body.content()));}
    @PostMapping("/api/agent/sessions/{session}/close") public Object close(HttpServletRequest r,@PathVariable String session){store.close(actor(r),session);return ApiResponse.success(Map.of("closed",true));}
    @PostMapping("/api/agent/runs/{run}/feedback") public Object feedback(HttpServletRequest r,@PathVariable String run,@Valid @RequestBody Feedback body){store.feedback(actor(r),run,body.rating(),Objects.toString(body.comment(),""));return ApiResponse.success(Map.of("saved",true));}
    @GetMapping("/api/admin/agent/runs") public Object runs(HttpServletRequest r){admin(r);return ApiResponse.success(store.adminList(false));}
    @GetMapping("/api/admin/agent/runs/{run}") public Object run(HttpServletRequest r,@PathVariable String run){admin(r);return ApiResponse.success(store.adminDetail(false,run));}
    @GetMapping("/api/admin/agent/runs/{run}/tool-calls") public Object tools(HttpServletRequest r,@PathVariable String run){admin(r);return ApiResponse.success(store.tools(run));}
    @GetMapping("/api/admin/agent/handoffs") public Object handoffs(HttpServletRequest r){admin(r);return ApiResponse.success(store.adminList(true));}
    @GetMapping("/api/admin/agent/handoffs/{no}") public Object handoff(HttpServletRequest r,@PathVariable String no){admin(r);return ApiResponse.success(store.adminDetail(true,no));}
    @PostMapping("/api/admin/agent/handoffs/{no}/process") public Object process(HttpServletRequest r,@PathVariable String no){store.process(admin(r).userId(),no,false,"");return ApiResponse.success(Map.of("saved",true));}
    @PostMapping("/api/admin/agent/handoffs/{no}/resolve") public Object resolve(HttpServletRequest r,@PathVariable String no,@Valid @RequestBody Resolution body){store.process(admin(r).userId(),no,true,body.resolution());return ApiResponse.success(Map.of("saved",true));}
    @GetMapping("/api/admin/agent/intent-releases") public Object releases(HttpServletRequest r){admin(r);return ApiResponse.success(releases.list());}
    @GetMapping("/api/admin/agent/intent-releases/{no}") public Object release(HttpServletRequest r,@PathVariable String no){admin(r);return ApiResponse.success(releases.detail(no));}
    @PostMapping("/api/admin/agent/intent-releases") public Object build(HttpServletRequest r,@Valid @RequestBody ReleaseRequest body){return ApiResponse.success(Map.of("releaseNo",releases.create(admin(r).userId(),body.evaluations())));}
    @PostMapping("/api/admin/agent/intent-releases/{no}/activate") public Object activate(HttpServletRequest r,@PathVariable String no){admin(r);releases.activate(no);return ApiResponse.success(Map.of("saved",true));}
    @PostMapping("/api/admin/agent/intent-releases/{no}/revoke") public Object revoke(HttpServletRequest r,@PathVariable String no){admin(r);releases.revoke(no);return ApiResponse.success(Map.of("saved",true));}
}

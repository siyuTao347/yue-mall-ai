package com.example.agent.customer;

import api.agent.AgentActor;
import api.context.TraceContext;
import api.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.example.agent.customer.CustomerJson.error;

@RestController
public class CustomerController {
    private final CustomerStore store;private final CustomerRunner runner;private final IntentReleaseService releases;private final CustomerProperties properties;
    public CustomerController(CustomerStore store,CustomerRunner runner,IntentReleaseService releases){this(store,runner,releases,new CustomerProperties());}
    @Autowired public CustomerController(CustomerStore store,CustomerRunner runner,IntentReleaseService releases,CustomerProperties properties){this.store=store;this.runner=runner;this.releases=releases;this.properties=properties;}
    public record Send(@NotBlank @Size(max=2000) String content,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String clientMessageId){}
    public record Feedback(@Min(1) @Max(5) int rating,@Size(max=512) String comment){}
    public record Resolution(@NotBlank @Size(max=512) String resolution){}
    public record ReleaseRequest(@NotNull @Size(min=3,max=200) List<IntentReleaseService.Evaluation> evaluations){}

    private static AgentActor admin(AgentActor actor){if(!"ADMIN".equalsIgnoreCase(actor.role()))throw error(403,"AGENT_PERMISSION_DENIED","仅管理员可访问");return actor;}

    @PostMapping("/api/agent/sessions") public Object create(AgentActor actor){return ApiResponse.success(store.create(actor));}
    @GetMapping("/api/agent/sessions/{session}") public Object session(AgentActor actor,@PathVariable String session){return ApiResponse.success(store.session(actor,session));}
    @PostMapping("/api/agent/sessions/{session}/messages") public Object send(AgentActor actor,@PathVariable String session,@Valid @RequestBody Send body){return ApiResponse.success(runner.send(actor,session,body.clientMessageId(),body.content()));}
    @PostMapping(value="/api/agent/sessions/{session}/messages/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(AgentActor actor,@PathVariable String session,@Valid @RequestBody Send body){
        SseEmitter emitter=new SseEmitter(properties.getRunTimeoutMs()+10000L);
        String traceId=TraceContext.getTraceId();
        Thread.startVirtualThread(()->{
            if(traceId!=null)TraceContext.set(traceId);
            try {
                runner.send(actor,session,body.clientMessageId(),body.content(),
                    (stage,label)->sendEvent(emitter,"stage.progress",Map.of("stage",stage,"label",label)),
                    delta->sendEvent(emitter,"answer.delta",Map.of("delta",delta)),
                    event->{String name=event.path("event").asText("run.completed");JsonNode payload=event.has("result")?event.path("result"):event;
                        if(payload==event&&event.isObject()){payload=event.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove("event");}
                        sendEvent(emitter,name,payload);});
                emitter.complete();
            }catch(Exception failure){
                String code="AGENT_STREAM_FAILED",msg="暂时无法完成查询，请稍后重试。";
                for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                    if(cause instanceof com.example.agent.exception.RagApiException ragEx){code=ragEx.errorCode();msg=ragEx.getMessage();break;}
                    if(cause instanceof api.exception.BizException bizEx){code=bizEx.getErrorCode();msg=bizEx.getMessage();break;}
                }
                sendEvent(emitter,"run.failed",Map.of("errorCode",code,"answer",Objects.toString(msg,"暂时无法完成查询，请稍后重试。")));
                emitter.complete();
            }finally{TraceContext.clear();}
        });
        return emitter;
    }
    @PostMapping("/api/agent/sessions/{session}/close") public Object close(AgentActor actor,@PathVariable String session){store.close(actor,session);return ApiResponse.success(Map.of("closed",true));}
    @PostMapping("/api/agent/runs/{run}/feedback") public Object feedback(AgentActor actor,@PathVariable String run,@Valid @RequestBody Feedback body){store.feedback(actor,run,body.rating(),Objects.toString(body.comment(),""));return ApiResponse.success(Map.of("saved",true));}

    @GetMapping("/api/admin/agent/runs") public Object runs(AgentActor actor){admin(actor);return ApiResponse.success(store.adminList(false));}
    @GetMapping("/api/admin/agent/runs/{run}") public Object run(AgentActor actor,@PathVariable String run){admin(actor);return ApiResponse.success(store.adminDetail(false,run));}
    @GetMapping("/api/admin/agent/runs/{run}/tool-calls") public Object tools(AgentActor actor,@PathVariable String run){admin(actor);return ApiResponse.success(store.tools(run));}
    @GetMapping("/api/admin/agent/runs/{run}/evaluation-evidence") public Object evaluationEvidence(AgentActor actor,@PathVariable String run){admin(actor);return ApiResponse.success(store.evaluationEvidence(run));}
    @GetMapping("/api/admin/agent/handoffs") public Object handoffs(AgentActor actor){admin(actor);return ApiResponse.success(store.adminList(true));}
    @GetMapping("/api/admin/agent/handoffs/{no}") public Object handoff(AgentActor actor,@PathVariable String no){admin(actor);return ApiResponse.success(store.adminDetail(true,no));}
    @PostMapping("/api/admin/agent/handoffs/{no}/process") public Object process(AgentActor actor,@PathVariable String no){store.process(admin(actor).userId(),no,false,"");return ApiResponse.success(Map.of("saved",true));}
    @PostMapping("/api/admin/agent/handoffs/{no}/resolve") public Object resolve(AgentActor actor,@PathVariable String no,@Valid @RequestBody Resolution body){store.process(admin(actor).userId(),no,true,body.resolution());return ApiResponse.success(Map.of("saved",true));}
    @GetMapping("/api/admin/agent/intent-releases") public Object releases(AgentActor actor){admin(actor);return ApiResponse.success(releases.list());}
    @GetMapping("/api/admin/agent/intent-releases/{no}") public Object release(AgentActor actor,@PathVariable String no){admin(actor);return ApiResponse.success(releases.detail(no));}
    @PostMapping("/api/admin/agent/intent-releases") public Object build(AgentActor actor,@Valid @RequestBody ReleaseRequest body){return ApiResponse.success(Map.of("releaseNo",releases.create(admin(actor).userId(),body.evaluations())));}
    @PostMapping("/api/admin/agent/intent-releases/{no}/activate") public Object activate(AgentActor actor,@PathVariable String no){admin(actor);releases.activate(no);return ApiResponse.success(Map.of("saved",true));}
    @PostMapping("/api/admin/agent/intent-releases/{no}/revoke") public Object revoke(AgentActor actor,@PathVariable String no){admin(actor);releases.revoke(no);return ApiResponse.success(Map.of("saved",true));}

    private static void sendEvent(SseEmitter emitter,String name,Object data){try{emitter.send(SseEmitter.event().name(name).data(data));}catch(Exception ignored){}}
}

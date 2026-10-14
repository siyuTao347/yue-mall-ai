package com.example.agent.web;

import api.agent.AgentActor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AgentActorArgumentResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType()==AgentActor.class;
    }

    @Override public Object resolveArgument(MethodParameter parameter,ModelAndViewContainer mavContainer,
                                             NativeWebRequest webRequest,WebDataBinderFactory binderFactory) {
        HttpServletRequest request=webRequest.getNativeRequest(HttpServletRequest.class);
        Object cached=request.getAttribute(AgentActorResolver.REQUEST_ATTRIBUTE);
        if(cached instanceof AgentActor actor)return actor;
        AgentActor actor=AgentActorResolver.resolve(request);
        request.setAttribute(AgentActorResolver.REQUEST_ATTRIBUTE,actor);
        return actor;
    }
}

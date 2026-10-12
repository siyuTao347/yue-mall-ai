package com.example.agent.customer;

import api.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class CustomerAuthTest {
    @Test void rejectsSpoofedHeadersWithoutValidToken(){var request=new MockHttpServletRequest();request.addHeader("X-User-Id","1");request.addHeader("X-User-Role","ADMIN");assertThrows(RuntimeException.class,()->CustomerController.actor(request));}
    @Test void derivesRoleFromSignedTokenAndRejectsConflictingIdentity(){var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+JwtUtil.generateToken(5L,"","","USER"));request.addHeader("X-User-Role","ADMIN");assertEquals("USER",CustomerController.actor(request).role());request.addHeader("X-User-Id","6");assertThrows(RuntimeException.class,()->CustomerController.actor(request));}
}

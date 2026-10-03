package org.andre.construx.shared.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RestControllerTest {
    @GetMapping("/api/test")
    public String testEndpoint(){
        return "Hello   world!";
    }

}

package org.andre.construx.shared.storage.controller;

import org.andre.construx.shared.storage.ObjectStorage;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
public class S3Controller {

    @PutMapping("/upload")
    public void upload(ObjectStorage object){


    }


}

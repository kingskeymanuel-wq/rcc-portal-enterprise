package com.ecobank.rccportal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${quality.audio.storage-dir}")
    private String storageDir;

    @Value("${rcc.uploads.photos-dir}")
    private String photosDir;

    @Value("${rcc.uploads.kb-files-dir}")
    private String kbFilesDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/audio/**")
                .addResourceLocations("file:" + storageDir.replace("\\", "/") + "/");
        registry.addResourceHandler("/uploaded-photos/**")
                .addResourceLocations("file:" + photosDir.replace("\\", "/") + "/");
        registry.addResourceHandler("/kb-files/**")
                .addResourceLocations("file:" + kbFilesDir.replace("\\", "/") + "/");
    }
}
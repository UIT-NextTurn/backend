package com.nextturn.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration routing documentation paths to the Scalar UI reference.
 */
@Configuration
public class ScalarConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/docs").setViewName("forward:/scalar.html");
        registry.addViewController("/docs/").setViewName("forward:/scalar.html");
        registry.addViewController("/scalar").setViewName("forward:/scalar.html");
        registry.addViewController("/scalar/").setViewName("forward:/scalar.html");
    }
}

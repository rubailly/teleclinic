package rw.teleclinic.core.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The browser apps live in folders; their index pages are served for the folder URL. */
@Configuration
class StaticPages implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/clinician/").setViewName("forward:/clinician/index.html");
    }
}

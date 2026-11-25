package com.exchange.architecture.fixtures.beanassembly

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

// 자동 등록 위반을 읽는 예제다. 이 클래스들로 Spring 컨텍스트를 시작하지 않는다.
class PlainUseCase

class PlainStorage

@Service class AutoRegisteredUseCase

@Component class AutoRegisteredWorker

@Repository class AutoRegisteredStorage

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Component
annotation class BusinessComponent

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@BusinessComponent
annotation class ComposedBusinessComponent

@ComposedBusinessComponent class ComposedWorker

@RestController class HttpController

@RestControllerAdvice class HttpAdvice

@Controller class HtmlController

@ControllerAdvice class HtmlAdvice

@SpringBootApplication class FixtureApplication

@RestController @Service
class MixedController

@Configuration @Repository
class MixedConfig

@Configuration
class ExplicitAssemblyConfig {
    @Bean fun workflow() = PlainUseCase()

    @Bean fun storage() = PlainStorage()
}

@Configuration
class OutsideConfig {
    @Bean fun workflow() = PlainUseCase()
}

class UnconfiguredFactory {
    @Bean fun workflow() = PlainUseCase()
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Bean
annotation class ComposedBean

class OutsideComposedFactory {
    @ComposedBean fun workflow() = PlainUseCase()
}

package com.exchange.architecture.support

import java.nio.file.Files
import java.nio.file.Path

/**
 * 실제로 컴파일된 예제의 바이트를 [root] 아래에 복사한다.
 * 테스트는 이 임시 파일들로 누락·중복·오염 상황을 만들며 운영 출력은 수정하지 않는다.
 */
fun fixtureOutput(root: Path, vararg types: Class<*>): Path {
    Files.createDirectories(root)
    types.forEach { type ->
        val relative = type.name.replace('.', '/') + ".class"
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        val bytes = requireNotNull(type.getResourceAsStream("/$relative")) {
            "Fixture bytecode is missing: $relative"
        }.use { it.readBytes() }
        Files.write(target, bytes)
    }
    return root
}

plugins {
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation(kotlin("test-junit"))
}

sourceSets {
    main {
        kotlin {
            setSrcDirs(listOf("../common/src/main/kotlin"))
            include("**/SourceLanguage.kt")
            include("**/parse/FilmoParser.kt", "**/parse/SerienStreamParser.kt")
            include("**/parse/Images.kt", "**/parse/Model.kt")
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf(
                "../common/src/test/kotlin",
                "../Filmo/src/test/kotlin",
                "../SerienStream/src/test/kotlin",
            ))
            include("**/LiveFetch.kt", "**/*LiveTest.kt")
        }
    }
}

tasks.test {
    useJUnit()
    doFirst { check(System.getenv("SMOKE") == "1") { "Set SMOKE=1 to run live tests" } }
    environment("SMOKE", System.getenv("SMOKE") ?: "")
}

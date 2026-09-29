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
            setSrcDirs(listOf("../../common/src/main/kotlin"))
            include("**/SourceLanguage.kt", "**/parse/*.kt")
        }
    }
    test {
        kotlin {
            setSrcDirs(listOf("../../Filmo/src/test/kotlin"))
            include("**/Fixtures.kt", "**/*ParserTest.kt")
        }
        resources.setSrcDirs(listOf("../../Filmo/src/test/resources"))
    }
}

tasks.test {
    useJUnit()
}

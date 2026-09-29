#!/usr/bin/env python3
"""Prepare the pinned upstream Gradle plugin for a reproducible local build.

The project does not use the ADB deploy task. Omitting it also removes the
plugin's JitPack-only jadb dependency.
"""
from pathlib import Path
import subprocess

REVISION = "cce1b8d84dc796b8da4a92a64cedfb046d1937b3"
root = Path(__file__).resolve().parents[1]
plugin = root / "cloudstream-gradle"
if not plugin.exists():
    subprocess.run(
        ["git", "clone", "https://github.com/recloudstream/gradle.git", str(plugin)],
        check=True,
    )
    subprocess.run(["git", "-C", str(plugin), "checkout", "--detach", REVISION], check=True)

actual = subprocess.check_output(
    ["git", "-C", str(plugin), "rev-parse", "HEAD"], text=True
).strip()
if actual != REVISION:
    raise SystemExit(f"Unexpected Cloudstream plugin revision: {actual}")

build = plugin / "build.gradle.kts"
tasks = plugin / "src/main/kotlin/com/lagradost/cloudstream3/gradle/tasks/Tasks.kt"
dependency = '    implementation("com.github.vidstige:jadb:master-SNAPSHOT")\n'
registration = '''    project.tasks.register("deployWithAdb", DeployWithAdbTask::class.java) {
        it.group = TASK_GROUP
        it.dependsOn("make")
    }
'''
exclude = '''
// The repository only publishes plugins; it does not deploy to ADB devices.
sourceSets {
    main {
        kotlin.exclude("**/DeployWithAdbTask.kt")
    }
}
'''
build_text = build.read_text()
tasks_text = tasks.read_text()
if dependency not in build_text and exclude not in build_text:
    raise SystemExit("Upstream jadb dependency changed; review the patch")
if registration not in tasks_text and "deployWithAdb" in tasks_text:
    raise SystemExit("Upstream deploy task registration changed; review the patch")
if dependency in build_text:
    build_text = build_text.replace(dependency, "")
if exclude not in build_text:
    build_text += exclude
tasks_text = tasks_text.replace(registration, "")
build.write_text(build_text)
tasks.write_text(tasks_text)
print(f"Prepared Cloudstream Gradle plugin at {REVISION}")

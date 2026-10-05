plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("nu.studer.jooq") version "9.0"
}

group = "com.akash"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	jooqGenerator("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Force every org.jooq artifact (runtime AND codegen) to one modern version.
// 3.19.1 (Boot's default) has a Postgres FK-metadata bug (KEY_SEQ vs key_seq)
// and its codegen/meta split caused AbstractMethodError. 3.21.7 fixes both,
// and pinning it everywhere keeps generated code and runtime in sync.
configurations.all {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.jooq" && requested.name.startsWith("jooq")) {
			useVersion("3.21.7")
		}
	}
}

jooq {
	version.set("3.21.7")
	configurations {
		create("main") {
			generateSchemaSourceOnCompilation.set(false)  // don't regenerate on every build
			jooqConfiguration.apply {
				jdbc.apply {
					driver = "org.postgresql.Driver"
					url = "jdbc:postgresql://localhost:5432/ledger"
					user = "ledger"
					password = "ledger"
				}
				generator.apply {
					name = "org.jooq.codegen.DefaultGenerator"
					database.apply {
						name = "org.jooq.meta.postgres.PostgresDatabase"
						inputSchema = "public"
						// only your tables, skip flyway's bookkeeping table
						excludes = "flyway_schema_history"
					}
					generate.apply {
						isRecords = true
						isPojos = false
						isDeprecated = false
					}
					target.apply {
						packageName = "com.akash.ledger.jooq"
						directory = "src/generated/jooq"
					}
				}
			}
		}
	}
}
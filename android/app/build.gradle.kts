plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cg.radari.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cg.radari.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Mapbox javni token: local.properties -> manifest placeholder.
        // Ako token nije postavljen, aplikacija se i dalje gradi (mapa ce javiti gresku,
        // ali fallback Android Auto ekran bez mape radi).
        val props = java.util.Properties().apply {
            val f = rootDir.resolve("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val publicToken = props.getProperty("MAPBOX_PUBLIC_TOKEN")
            ?: System.getenv("MAPBOX_PUBLIC_TOKEN")
            ?: ""
        manifestPlaceholders["MAPBOX_PUBLIC_TOKEN"] = publicToken
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"$publicToken\"")

        // Supabase (patrole ekipe) — opciono; prazno = modul se tiho iskljuci.
        fun prop(name: String) = props.getProperty(name) ?: System.getenv(name) ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"${prop("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${prop("SUPABASE_ANON_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Android Auto (car app library)
    implementation("androidx.car.app:app:1.7.0")
    // Mapbox SDK v11 + Android Auto ekstenzija (povlaci com.mapbox.maps:android:11.15.2)
    implementation("com.mapbox.extension:maps-androidauto:11.15.2")

    // Compose za telefon UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Cista logika (AlertEngine, Geo) se testira bez Androida.
    testImplementation("junit:junit:4.13.2")
}

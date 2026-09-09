@echo off
REM Gradle wrapper jar nije commitovan (binarni fajl).
REM Prvi put projekat otvori u Android Studio-u: on procita gradle/wrapper/gradle-wrapper.properties,
REM skine Gradle 8.9 i sam napravi wrapper jar.
REM Alternativa iz komandne linije, ako imas instaliran Gradle 8.9+:
REM     gradle wrapper
REM     gradlew assembleDebug
echo Otvori android/ u Android Studio-u, ili pokreni: gradle wrapper ^&^& gradlew assembleDebug

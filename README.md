# IA Local Android

Aplicación Android de chat en español con inferencia de texto en el teléfono. La versión 0.1.0 está diseñada para el Redmi Note 14 Pro 4G con 8 GB de RAM. Usa Kotlin, Jetpack Compose, SQLite y llama.cpp sobre CPU ARM64.

## Instalar desde el celular

1. Descarga **IA-Local-Android.apk**. Si lo obtienes de GitHub Actions, abre el flujo **APK Android**, elige una ejecución verde y descarga el artefacto **IA-Local-Android-ARM64**. Inicia sesión con acceso al repositorio privado y extrae el ZIP desde la app Archivos.
2. Abre el APK. Si HyperOS lo solicita, habilita **Instalar aplicaciones desconocidas** para la aplicación que abrió el archivo y confirma la instalación. El APK de prueba requiere Android 9 o posterior y ARM64.
3. Abre **IA Local Android → Configurar modelo → Descargar Qwen3.5-2B**. Deja la aplicación abierta hasta que termine la verificación. El archivo pesa **1,28 GB**.
4. Vuelve al chat. Activa modo avión y comprueba que la Wi-Fi también esté apagada. Escribe «¿Cuánto es 6 × 7?» y luego «Escribe una función Python que devuelva el doble de un número».
5. Cierra y vuelve a abrir la aplicación. Abre **Historial** para comprobar que la conversación siga disponible.

El APK de GitHub Actions se firma para pruebas. Dos compilaciones generadas en entornos limpios pueden tener firmas distintas y Android puede rechazar la actualización. Desinstalar borra el historial y los modelos. Para cambiar un modelo usa **Modelos locales** dentro de la aplicación.

## Uso

- El botón junto al mensaje cambia a **Detener** mientras se genera la respuesta. El historial conserva la parte ya recibida.
- Los bloques de código tienen desplazamiento horizontal y un botón para copiar. La aplicación muestra el código como texto.
- **Ajustes → Memoria y contexto** controla el contexto, el máximo de salida y los hilos. La configuración inicial es 2.048 tokens de contexto, hasta 512 de respuesta y cuatro hilos.
- **Modelos locales → Importar GGUF** copia un modelo de texto Qwen3.5 a almacenamiento privado. Introduce el SHA-256 que te dio quien publicó el archivo y elige el GGUF. Una versión con otro hash queda disponible junto a la anterior.
- Si se interrumpe una descarga, puedes iniciarla de nuevo. El tamaño y el SHA-256 del archivo se comprueban antes de publicarlo en la biblioteca de modelos.

## Compilar y probar

Para compilar en Linux, usa JDK 17, SDK Android 35, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1, Git, curl y herramientas POSIX. El Gradle Wrapper fija Gradle 8.11.1 y verifica su SHA-256.

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
scripts/fetch-llama.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Con un Android ARM64 conectado a ADB, ejecuta `./gradlew :app:connectedDebugAndroidTest` para las pruebas instrumentadas. El flujo de GitHub Actions guarda el APK firmado, su SHA-256 y los informes de compilación.

- [Arquitectura y licencias](docs/arquitectura.md)
- [Verificación y pruebas en el Redmi](docs/verificacion.md)
- [Personalización con LoRA o QLoRA](training/README.md)

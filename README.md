# IA Local Android

Es una app para usar modelos de Ia localmente. La versión 0.4.0 ofrece chat en español con una interfaz de cristal violeta y azul y está diseñada para el Redmi Note 14 Pro 4G con 8 GB de RAM. Usa Kotlin, Jetpack Compose, SQLite y llama.cpp sobre CPU ARM64.

## Instalar desde el celular

1. Descarga **IA-Local-Android.apk**. Si lo obtienes de GitHub Actions, abre el flujo **APK Android**, elige una ejecución verde y descarga el artefacto **IA-Local-Android-ARM64**. Inicia sesión con acceso al repositorio privado y extrae el ZIP desde la app Archivos.
2. Abre el APK. Si HyperOS lo solicita, habilita **Instalar aplicaciones desconocidas** para la aplicación que abrió el archivo y confirma la instalación. El APK de prueba requiere Android 9 o posterior y ARM64.
3. Abre **IA Local Android → Configurar modelo → Descargar Qwen3.5-2B**. Deja la aplicación abierta hasta que termine la verificación. El archivo pesa **1,28 GB**.
4. Vuelve al chat. Activa modo avión y comprueba que la Wi-Fi también esté apagada. Escribe «¿Cuánto es 6 × 7?» y luego «Escribe una función Python que devuelva el doble de un número».
5. Cierra y vuelve a abrir la aplicación. Abre **Historial** para comprobar que la conversación siga disponible.

El APK estándar mantiene el identificador `com.ialocal.android` y el historial de SQLite. Android exige la misma firma para actualizar una instalación existente. Si ya tienes una versión firmada para pruebas, descarga **IA-Local-Android-vista-previa-ARM64**. El APK 0.4 usa un identificador de vista previa propio y puede instalarse junto a la app estándar y a las vistas previas anteriores. Su historial se guarda en un espacio independiente. Puedes probar el rediseño conservando intacta tu instalación anterior. Para instalar por primera vez usa el APK estándar.

## Uso

- En **Ajustes → Apariencia** puedes elegir tema claro, oscuro o del sistema y reducir animaciones.
- **Historial** incluye un buscador por título que admite palabras sin tilde.
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
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleVisualPreview :app:assembleDebugAndroidTest
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Con un Android ARM64 conectado a ADB, ejecuta `./gradlew :app:connectedDebugAndroidTest` para las pruebas instrumentadas. El flujo de GitHub Actions guarda ambos APK firmados, sus SHA-256, las capturas de Compose y los informes.

- [Fase de inferencia y banco en español](docs/fase-inferencia.md)
- [Auditoría original de LFM3 y Nanbeige en la 0.3](docs/candidatos-gguf.md)
- [Rediseño y capturas](docs/diseno-liquid-glass.md)
- [Arquitectura y licencias](docs/arquitectura.md)
- [Verificación y pruebas en el Redmi](docs/verificacion.md)
- [Personalización con LoRA o QLoRA](training/README.md)

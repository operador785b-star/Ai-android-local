# IA Local Android

Es una app para usar modelos de IA localmente. La versión 0.4.5 ofrece chat en español con una interfaz de cristal violeta y azul y está diseñada para el Redmi Note 14 Pro 4G con 8 GB de RAM. Usa Kotlin, Jetpack Compose, SQLite y llama.cpp sobre CPU ARM64.

## Instalar desde el celular

1. Descarga la vista previa de la 0.4.5 en ZIP y extráela con **Archivos**. [ZIP de vista previa 0.4.5](https://raw.githubusercontent.com/operador785b-star/Ai-android-local/descargas-apk/IA-Local-Android-0.4.5-vista-previa.zip)
2. Abre el APK. Si HyperOS lo solicita, habilita **Instalar aplicaciones desconocidas** para la aplicación que abrió el archivo y confirma la instalación. El APK de prueba requiere Android 9 o posterior y ARM64.
3. Abre **IA Local Android → Configurar modelo → Descargar Liquid AI LFM3-1.7B**. El archivo pesa **1,10 GB**. La aplicación lo verifica, lo guarda en su almacenamiento privado y lo activa. Espera a que aparezca **En uso**.
4. Vuelve al chat y escribe «Hola». La pregunta y la respuesta quedan en el historial. Con el modelo instalado puedes repetir la prueba en modo avión con Wi-Fi apagada.
5. Cierra y vuelve a abrir la aplicación. Abre **Historial** para comprobar que la conversación siga disponible.

El APK estándar mantiene el identificador `com.ialocal.android` y el historial de SQLite. Android exige la misma firma para actualizar una instalación existente. La vista previa 0.4.5 usa un identificador propio y puede instalarse junto a la app estándar y a las vistas previas anteriores. Su historial y sus modelos se guardan en un espacio independiente.

## Uso

- En **Ajustes → Apariencia** puedes elegir tema claro, oscuro o del sistema y reducir animaciones.
- **Historial** incluye un buscador por título que admite palabras sin tilde.
- El botón junto al mensaje cambia a **Detener** mientras se genera la respuesta. El historial conserva la parte ya recibida.
- Con **LFM3 activo**, escribe `/json` al inicio de un mensaje para pedir un objeto JSON sin Markdown. Por ejemplo, envía `/json Devuelve las claves ciudad con valor Lima y dias con el entero 3`. Indica los datos que necesitas y espera a que termine la respuesta. Si la detienes o llega al límite de salida, puede quedar incompleta. La restricción controla la sintaxis y LFM3 sigue eligiendo las claves y los valores.
- Los bloques de código tienen desplazamiento horizontal y un botón para copiar. La aplicación muestra el código como texto.
- **Ajustes → Memoria y contexto** ofrece Económico, Equilibrado y Máximo rendimiento, además de los ajustes manuales de contexto, salida e hilos. La configuración inicial es Equilibrado, con 2.048 tokens de contexto, hasta 512 de respuesta y dos hilos.
- **Modelos locales → Importar GGUF** sirve para un modelo que ya tengas como archivo `.gguf` compatible. Si viene en ZIP, extráelo a **Descargas** y elige el `.gguf`. El nombre es opcional. La app comprueba la estructura y calcula la huella durante la copia para reconocer duplicados. La descarga de LFM3 desde el catálogo queda lista para chatear sin pasar por el selector de archivos.
- El catálogo ofrece Qwen3.5-2B (1,28 GB), LFM3-1.7B (1,10 GB) y Nanbeige 4.2 3B (2,68 GB). Cada modelo se descarga por separado y se puede activar o eliminar desde **Modelos locales**. Si se interrumpe una descarga, puedes iniciarla de nuevo. El tamaño y el SHA-256 del archivo se comprueban antes de publicarlo en la biblioteca de modelos.
- **Ajustes → Diagnóstico local** permite copiar o guardar el JSON con métricas por etapa sin incluir el contenido de los chats.

## Compilar y probar

Para compilar en Linux, usa JDK 17, SDK Android 35, Build Tools 35.0.0, NDK 28.2.13676358, CMake 3.22.1, Git, curl y herramientas POSIX. El Gradle Wrapper fija Gradle 8.11.1 y verifica su SHA-256.

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
scripts/fetch-llama.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleVisualPreview :app:assembleDebugAndroidTest
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Con un Android ARM64 conectado a ADB, ejecuta `./gradlew :app:connectedDebugAndroidTest` para las pruebas instrumentadas. El flujo de GitHub Actions guarda ambos APK firmados, sus SHA-256, las capturas de Compose y los informes.

- [Fase de inferencia y banco en español](docs/fase-inferencia.md)
- [Rendimiento de la 0.4.3 y pruebas en el Redmi](docs/rendimiento-android.md)
- [Pruebas del modo JSON de LFM3](docs/lfm3-json.md)
- [Auditoría original de LFM3 y Nanbeige en la 0.3](docs/candidatos-gguf.md)
- [Rediseño y capturas](docs/diseno-liquid-glass.md)
- [Arquitectura y licencias](docs/arquitectura.md)
- [Verificación y pruebas en el Redmi](docs/verificacion.md)
- [Personalización con LoRA o QLoRA](training/README.md)

# Arquitectura de la versión 0.1.0

- **Interfaz**. Jetpack Compose muestra el chat, el historial, la biblioteca de modelos y los ajustes. El ViewModel conserva el estado durante rotaciones, transmite fragmentos de la respuesta y guarda el avance aproximadamente una vez por segundo.
- **Historial**. SQLite inserta el mensaje y la respuesta pendiente en una transacción. Si Android cierra el proceso, al abrir la aplicación se marcan las respuestas pendientes como interrumpidas. Los chats se guardan íntegros. Cada petición usa los turnos completos más recientes que caben según el tokenizador del modelo.
- **Inferencia**. La interfaz `InferenceEngine` recibe el archivo del modelo, el contexto reciente y el presupuesto de tokens. `LlamaEngine` llama al motor C++ por JNI. Se usa CPU, pesos mapeados con mmap y un solo turno activo por sesión. Desde la 0.4.3, la sesión puede mantener el modelo y el contexto para el siguiente turno y usar un prefijo de tokens validado. El modo conversacional usa la plantilla del perfil. [Retención, cancelación y rendimiento](rendimiento-android.md)
- **Modelos**. La descarga fijada usa HTTPS, longitud y SHA-256 conocidos. Las importaciones comprueban la estructura y la presencia de los tensores declarados en el GGUF. La app calcula la huella al copiar el archivo para identificar cada versión. Los datos se escriben primero en un archivo temporal, se sincronizan y se mueven de forma atómica. La caché de importación se limpia al iniciar si quedó una copia temporal.
- **Privacidad**. El historial y preferencias se guardan dentro del espacio privado de la aplicación. Las reglas de Android excluyen esos datos de copias y migraciones. El permiso de red sirve para descargar el GGUF. La ruta de inferencia recibe el archivo local y los mensajes en el proceso de Android.
- **Extensión**. Para voz, un transcriptor puede entregar texto a la misma acción de envío y un sintetizador puede leer las respuestas desde el estado observable. Qwen3.5-4B podrá evaluarse como GGUF importado del mismo perfil de texto. El rendimiento y consumo del 4B en el Redmi requieren una medición aparte.

## Modelo fijado

| Propiedad | Valor |
| --- | --- |
| Modelo original | Qwen/Qwen3.5-2B, conversacional |
| Cuantización | Unsloth, Q4_K_M, solo el archivo de texto |
| Tamaño | 1.280.835.840 bytes |
| Revisión | `f6d5376be1edb4d416d56da11e5397a961aca8ae` |
| SHA-256 | `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` |
| Motor | llama.cpp b8999, `b97ebdc98f6053604a19d861c08d8087601b96e0` |

[Modelo original](https://huggingface.co/Qwen/Qwen3.5-2B) · [Archivo GGUF fijado](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/blob/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf) · [Revisión del motor](https://github.com/ggml-org/llama.cpp/tree/b97ebdc98f6053604a19d861c08d8087601b96e0)

## Licencias y dependencias

- llama.cpp y ggml se distribuyen bajo MIT. El APK incluye la licencia de llama.cpp. El flujo de compilación descarga su código fuente fijado y verifica su SHA-256. [Licencia de llama.cpp](https://github.com/ggml-org/llama.cpp/blob/b97ebdc98f6053604a19d861c08d8087601b96e0/LICENSE)
- Qwen3.5-2B y la cuantización seleccionada declaran Apache 2.0. El APK incluye la licencia del modelo original y descarga los pesos por separado. Si redistribuyes una versión ajustada, conserva la licencia, los avisos aplicables y la identificación del modelo base. [Licencia del modelo original](https://huggingface.co/Qwen/Qwen3.5-2B/blob/15852e8c16360a2fea060d615a32b45270f8a8fc/LICENSE)
- Kotlin, Kotlin Coroutines, AndroidX y Jetpack Compose se publican bajo Apache 2.0. Gradle y Android Gradle Plugin son herramientas de compilación y JUnit 4 es una dependencia de pruebas bajo EPL 1.0. La biblioteca C++ del NDK se rige por la licencia de LLVM. [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) · [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) · [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) · [JUnit 4](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) · [LLVM libc++](https://github.com/llvm/llvm-project/blob/llvmorg-19.1.0/libcxx/LICENSE.TXT)
- AGP 8.10.1 es compatible con Gradle 8.11.1, JDK 17 y Build Tools 35.0.0. El APK usa únicamente `arm64-v8a` con instrucciones base ARMv8.2-A para el procesador objetivo. [Compatibilidad de AGP](https://developer.android.com/build/releases/past-releases/agp-8-10-0-release-notes) · [Redmi Note 14 Pro 4G](https://www.mi.com/global/product/redmi-note-14-pro/specs/)

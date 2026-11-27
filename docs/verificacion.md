# Verificación

Los resultados de la versión 0.4 están en la [fase de inferencia local](fase-inferencia.md). Este documento conserva el registro inicial de Qwen y el protocolo de prueba física.

## Verificación inicial en Codex Cloud

- Compilación del APK de depuración y del APK de pruebas con SDK 35 y NDK r28c. El APK contiene las bibliotecas ARM64.
- Diez pruebas unitarias sobre integridad GGUF, plantilla de chat, contexto, UTF-8 dividido entre fragmentos y visualización de código.
- Android Lint sin errores. Las advertencias restantes incluyen versiones de dependencias sugeridas y soporte opcional de otras ABI.
- Descarga completa del GGUF y comprobación SHA-256 contra el catálogo. Una cuantización evaluada primero incluía capas de predicción adicionales que esta revisión del motor no pudo cargar. El catálogo usa la variante de Unsloth que pasó la prueba.
- Inferencia local por JNI en Linux x86-64, con el mismo código C++ y el mismo GGUF del catálogo. El modelo respondió que seis por siete es cuarenta y dos y emitió una función Python `doble`. La prueba verificó transmisión en fragmentos, cancelación y rechazo de una entrada que excedía el contexto.
- Sintaxis de scripts de personalización y división reproducible del ejemplo JSONL. Dos ejecuciones con la misma semilla generaron los mismos archivos.

## Prueba pendiente en el Redmi

| Acción | Resultado esperado |
| --- | --- |
| Instalar y abrir el APK | Inicia la pantalla de chat sin fallos de carga |
| Descargar cada modelo Q4_K_M o importar un Qwen compatible | El modelo aparece en **Instalados** cuando termina la verificación |
| Activar modo avión, apagar la Wi-Fi y seleccionar un modelo instalado | El mismo modelo se puede seleccionar y responde por fragmentos sin conexión |
| Tocar **Detener** | El texto parcial permanece en el historial |
| Cerrar la aplicación y volver a abrir el chat | El hilo conserva los mensajes |
| Cambiar contexto entre 1.024 y 4.096 | El nuevo ajuste se aplica en el siguiente turno |
| Enviar un ejercicio y una petición de código | El modelo responde y se puede copiar el bloque de código |
| Generar varias respuestas con contexto de 2.048, dos hilos y salida de 128 tokens | Copiar **Ajustes → Diagnóstico local**, anotar temperatura y verificar que el sistema conserva la app en memoria |
| Eliminar un modelo instalado | Desaparece de **Instalados**, se libera el almacenamiento y el historial permanece disponible |

Las pruebas instrumentadas de pantalla y SQLite quedaron compiladas para ARM64. Siguen pendientes en Android. Los resultados del host prueban el motor y el archivo de pesos, mientras que el tiempo de respuesta, el consumo de RAM y la estabilidad térmica dependen del Redmi.

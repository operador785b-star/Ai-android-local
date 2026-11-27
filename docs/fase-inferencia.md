# Fase de inferencia local

## Motor y distribución

Se fijó llama.cpp v1.1.0, revisión `171527522610996f5f0acf12334e185156ca8f44`. Nanbeige entró en la rama oficial con el PR 25994 y LFM3 con el PR 31252. El paso del motor incluye perfiles de conversación distintos, memoria mapeada y prevalidación del GGUF. Los pesos se descargan por separado y el APK permanece sin GGUF. [Nanbeige en llama.cpp](https://github.com/ggml-org/llama.cpp/pull/25994) · [LFM3 en llama.cpp](https://github.com/ggml-org/llama.cpp/pull/31252) · [Motor fijado](https://github.com/ggml-org/llama.cpp/tree/171527522610996f5f0acf12334e185156ca8f44)

El administrador conserva el archivo parcial hasta cerrar la copia, comprobar tamaño y SHA-256 e identificar el perfil. Solo entonces publica el archivo por renombrado atómico. El motor vuelve a comprobar la arquitectura al cargar. La selección y la eliminación se bloquean durante la generación o una transferencia.

## Banco reproducible

El banco [es-local.jsonl](../evaluation/es-local.jsonl) tiene diez casos en español y compara perfiles con contexto de 2.048, dos hilos y semilla 1234. Cada caso lanza un proceso nuevo con los pesos ya descargados. Usa el JNI que compila la app. El perfil fijo del banco usa texto sin razonamiento oculto y empieza con el mismo prefijo de sistema que Android. El caso de programación analiza el AST para comprobar el nombre, el bucle y la ausencia de autorrecursión. Sus resultados miden estructura, y la corrección funcional del programa requiere pruebas adicionales.

```bash
scripts/fetch-llama.sh
cmake -S app/src/main/cpp -B build/inference-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/inference-host -j 2
javac -d build/inference-host scripts/NativeBridge.java
unshare -Urn -- python3 evaluation/run.py lfm3 /ruta/LFM3.gguf build/inference-host /tmp/lfm3.json
```

También se aceptan los perfiles `qwen35` y `nanbeige`. Los informes registran huella del modelo, salida, tiempo, tokens y código del motor. `unshare -Urn` requiere espacios de nombres de usuario habilitados en Linux.

**Ajustes → Diagnóstico local** conserva los últimos 16 intentos, registra las interrupciones recuperadas al volver a abrir la app y permite copiar JSON. El RSS es el máximo del proceso desde el arranque y puede incluir otras inferencias o el uso de memoria de la interfaz. La velocidad excluye la espera al primer fragmento. Los registros omiten el texto y contienen nombre del modelo, contexto, hilos y métricas.

## Personalización

El dataset inicial [fase1-es.jsonl](../training/fase1-es.jsonl) es un conjunto de ejemplo para reproducir preparación y validación. Usa `python training/check_split.py training/fase1-es.jsonl evaluation/es-local.jsonl` antes de crear los conjuntos con `python training/data.py`. Revisa también las duplicaciones por contenido antes de usar datos reales.

- **Qwen3.5**. El repositorio ya contiene scripts de LoRA/QLoRA, fusión y cuantización para el modelo base fijado.
- **LFM3**. Liquid AI documenta el ajuste con TRL. Primero hay que fijar Transformers y las implementaciones del modelo, comprobar que PEFT identifica las capas lineales elegidas y ensayar la fusión y la conversión de esas capas. QLoRA requiere además una prueba de carga, cálculo de gradiente y fusión sobre pesos originales. [Modelo y ajuste LFM3](https://huggingface.co/LiquidAI/LFM3-1.7B)
- **Nanbeige 4.2**. La ficha oficial documenta Transformers 4.45.1 y código de modelo propio. La arquitectura repite capas y la conversión GGUF conserva el número de vueltas. Para LoRA y QLoRA quedan por validar los módulos objetivo, la cuantización en GPU y la fusión sin cambiar esa semántica. [Modelo Nanbeige](https://huggingface.co/Nanbeige/Nanbeige4.2-3B) · [Conversor fijado](https://github.com/ggml-org/llama.cpp/blob/171527522610996f5f0acf12334e185156ca8f44/conversion/nanbeige.py)

El paso previo a autorizar un entrenamiento largo es cargar un GGUF exportado desde los pesos base y verificar que pasa el banco en Android. Después, un piloto corto puede producir un adaptador para repetir fusión, exportación y evaluación. El dataset de ejemplo comprueba el flujo y requiere ampliación para medir mejoras reales.

## Futuro agente de Android

El motor genera una propuesta estructurada que pasa a un catálogo explícito de herramientas. Cada herramienta declara su esquema de argumentos, si puede operar localmente, permisos Android y efecto esperado. Un ejecutor aplica validación por esquema, límites de tiempo y tamaño, y registra el resultado. La interfaz muestra la propuesta y pide confirmación para acciones sensibles concretas. Después de confirmar, el ejecutor usa los permisos y los ámbitos estrictamente necesarios. El texto del modelo no se interpreta como un comando de shell ni concede permisos.

Las primeras herramientas previstas son cálculo y transformación de tablas en memoria. Para abrir o modificar archivos se puede usar el selector de Android con autorización sobre un archivo específico. Las operaciones externas requieren una confirmación que describa su destinatario y efecto.

# Fase de inferencia local

## Motor y distribución

Se fijó llama.cpp v1.1.0, revisión `171527522610996f5f0acf12334e185156ca8f44`. Nanbeige entró en la rama oficial con el PR 25994 y LFM3 con el PR 31252. El paso del motor incluye perfiles de conversación distintos, memoria mapeada y prevalidación del GGUF. Los pesos se descargan por separado y el APK permanece sin GGUF. [Nanbeige en llama.cpp](https://github.com/ggml-org/llama.cpp/pull/25994) · [LFM3 en llama.cpp](https://github.com/ggml-org/llama.cpp/pull/31252) · [Motor fijado](https://github.com/ggml-org/llama.cpp/tree/171527522610996f5f0acf12334e185156ca8f44)

El administrador conserva el archivo parcial hasta cerrar la copia e identificar el perfil. En las descargas del catálogo, compara el tamaño y el SHA-256 con los valores publicados. En las importaciones a partir de la 0.4.1, la huella calculada sirve para identificar el archivo y reconocer duplicados. El motor comprueba la estructura y la extensión de los tensores antes de que el administrador publique el archivo por renombrado atómico. La selección y la eliminación se bloquean durante la generación o una transferencia.

## Banco reproducible

El banco [es-local.jsonl](../evaluation/es-local.jsonl) tiene diez casos en español y compara perfiles con contexto de 2.048, dos hilos y semilla 1234. Cada caso lanza un proceso nuevo con los pesos ya descargados. Usa el JNI que compila la app. El perfil fijo del banco usa texto sin razonamiento oculto y empieza con el mismo prefijo de sistema que Android. El caso de programación analiza el AST para comprobar el nombre, el bucle y la ausencia de autorrecursión. Sus resultados miden estructura, y la corrección funcional del programa requiere pruebas adicionales.

```bash
scripts/fetch-llama.sh
cmake -S app/src/main/cpp -B build/inference-host -DCMAKE_BUILD_TYPE=Release
cmake --build build/inference-host -j 2
javac -d build/inference-host scripts/NativeBridge.java
unshare -Urn -- python3 evaluation/run.py lfm3 /ruta/LFM3.gguf build/inference-host /tmp/lfm3.json
```

También se aceptan los perfiles `qwen35` y `nanbeige`. Añade `--cases evaluation/history-es.jsonl` para ejecutar el caso con un turno previo. Los informes registran huella del modelo, salida, tiempo, tokens y código del motor. `unshare -Urn` requiere espacios de nombres de usuario habilitados en Linux.

### Resultados previos al entrenamiento

Se verificó el SHA-256 de los tres GGUF completos y se ejecutó el mismo banco en Linux x86-64 sobre un AMD EPYC 9V74, con la red aislada. Los tres modelos generaron texto por JNI sin errores del motor, permitieron cancelar tras el primer fragmento y respondieron a una nueva inferencia en el mismo proceso. Los tres también recuperaron exactamente el dato del turno anterior en el caso adicional de historial.

| Modelo Q4_K_M | Casos superados | Mediana de tokens/s | Mediana al primer fragmento | Pico RSS de proceso |
| --- | ---: | ---: | ---: | ---: |
| Liquid AI LFM3-1.7B | 4/10 | 20,68 | 3,13 s | 1,84 GiB |
| Qwen3.5-2B | 7/10 | 15,47 | 2,63 s | 1,85 GiB |
| Nanbeige 4.2 3B | 8/10 | 4,93 | 8,40 s | 2,92 GiB |

La velocidad es la mediana de los casos con al menos dos tokens y tiempo de decodificación registrado. El tiempo al primer fragmento incluye la apertura del modelo y la evaluación del mensaje. El pico RSS es el máximo observado entre los diez procesos. Los tres modelos fallaron las comprobaciones estrictas de Fibonacci y de ecuaciones. Nanbeige pasó los tres formatos de tabla. Qwen pasó Markdown y JSON. LFM3 pasó CSV y JSON.

En Nanbeige se desactivó el búfer adicional de CPU para reempaquetar pesos. Con el búfer activo, el pico observado fue de 4,01 GiB y la mediana de velocidad fue de 5,54 tokens/s. Con el búfer desactivado, el pico bajó a 2,92 GiB. Las métricas corresponden al host Linux y se medirán aparte en el Redmi.

[Informe LFM3](../evaluation/results/lfm3.json) · [Informe Qwen](../evaluation/results/qwen35.json) · [Informe Nanbeige](../evaluation/results/nanbeige.json) · [Nanbeige con búfer adicional](../evaluation/results/nanbeige-con-reempaquetado.json) · [Pruebas de historial](../evaluation/results/historial)

**Ajustes → Diagnóstico local** conserva los últimos 16 intentos, registra las interrupciones recuperadas al volver a abrir la app y permite copiar JSON. El RSS es el máximo del proceso desde el arranque y puede incluir otras inferencias o el uso de memoria de la interfaz. La velocidad excluye la espera al primer fragmento. Los registros omiten el texto y contienen nombre del modelo, contexto, hilos y métricas.

En el teléfono quedan por verificar la descarga desde la interfaz, selección y borrado, arranque del motor ARM64 en modo avión con Wi-Fi apagada y la estabilidad con varias respuestas seguidas. Para comparar con el banco, usa 2.048 tokens de contexto y dos hilos. [Protocolo de prueba física](verificacion.md#prueba-pendiente-en-el-redmi)

## Personalización

El dataset inicial [fase1-es.jsonl](../training/fase1-es.jsonl) es un conjunto de ejemplo para reproducir preparación y validación. Usa `python training/check_split.py training/fase1-es.jsonl evaluation/es-local.jsonl` antes de crear los conjuntos con `python training/data.py`. Revisa también las duplicaciones por contenido antes de usar datos reales.

- **Qwen3.5**. El repositorio ya contiene scripts de LoRA/QLoRA, fusión y cuantización para el modelo base fijado.
- **LFM3**. Liquid AI documenta el ajuste con TRL. Primero hay que fijar Transformers y las implementaciones del modelo, comprobar que PEFT identifica las capas lineales elegidas y ensayar la fusión y la conversión de esas capas. QLoRA requiere además una prueba de carga, cálculo de gradiente y fusión sobre pesos originales. [Modelo y ajuste LFM3](https://huggingface.co/LiquidAI/LFM3-1.7B)
- **Nanbeige 4.2**. La ficha oficial documenta Transformers 4.45.1 y código de modelo propio. La arquitectura repite capas y la conversión GGUF conserva el número de vueltas. Para LoRA y QLoRA quedan por validar los módulos objetivo, la cuantización en GPU y la fusión sin cambiar esa semántica. [Modelo Nanbeige](https://huggingface.co/Nanbeige/Nanbeige4.2-3B) · [Conversor fijado](https://github.com/ggml-org/llama.cpp/blob/171527522610996f5f0acf12334e185156ca8f44/conversion/nanbeige.py)

El guion `training/probe_export.sh` toma un checkpoint local y un cuantizador de la misma revisión del motor. Convierte a GGUF, cuantiza y ejecuta el banco bajo un espacio de nombres sin red. Se comprobó su sintaxis. La conversión de un checkpoint personalizado sigue pendiente.

El paso previo a autorizar un entrenamiento largo es cargar un GGUF exportado desde los pesos base y verificar que pasa el banco en Android. Después, un piloto corto puede producir un adaptador para repetir fusión, exportación y evaluación. El dataset de ejemplo comprueba el flujo y requiere ampliación para medir mejoras reales.

## Futuro agente de Android

El motor genera una propuesta estructurada que pasa a un catálogo explícito de herramientas. Cada herramienta declara su esquema de argumentos, si puede operar localmente, permisos Android y efecto esperado. Un ejecutor aplica validación por esquema, límites de tiempo y tamaño, y registra el resultado. La interfaz muestra la propuesta y pide confirmación para acciones sensibles concretas. Después de confirmar, el ejecutor usa los permisos y los ámbitos estrictamente necesarios. El texto del modelo no se interpreta como un comando de shell ni concede permisos.

Las primeras herramientas previstas son cálculo y transformación de tablas en memoria. Para abrir o modificar archivos se puede usar el selector de Android con autorización sobre un archivo específico. Las operaciones externas requieren una confirmación que describa su destinatario y efecto.

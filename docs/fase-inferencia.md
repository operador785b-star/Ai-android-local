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

En la 0.4.2 la app muestra las etapas de carga, preparación de memoria, procesamiento del mensaje y generación. El diagnóstico registra la última etapa, el tamaño del GGUF y el contexto que asignó el motor. Tras una interrupción, incorpora el motivo de salida del proceso cuando Android 11 o posterior lo proporciona. El motor comprueba un límite de dos minutos antes del primer fragmento y admite la cancelación durante el procesamiento. Nanbeige ajusta la memoria de contexto a los tokens del mensaje y la salida reservada, dentro del máximo elegido.

Con el mensaje «Hola» y el Nanbeige Q4_K_M del catálogo, una ejecución por configuración en un host Linux limitado a dos CPU produjo el primer fragmento en 20,21 s con cuatro hilos de la 0.4.1 y en 7,49 s con dos hilos. El tiempo total fue de 151,93 s y 11,21 s respectivamente. Con la 0.4.2 y dos hilos, la misma petición tardó 6,54 s en emitir y 10,09 s en terminar. El contexto asignado fue de 512 tokens para esa prueba y el pico RSS bajó de 2,92 a 2,67 GiB. La prueba JNI `--nanbeige-start` también confirmó el orden de etapas, la cancelación durante el procesamiento y el código de demora inicial. Estos tiempos no incluyen mediciones del Redmi.

En el teléfono quedan por verificar la descarga desde la interfaz, selección y borrado, arranque del motor ARM64 en modo avión con Wi-Fi apagada y la estabilidad con varias respuestas seguidas. Para comparar con el banco, usa 2.048 tokens de contexto y dos hilos. [Protocolo de prueba física](verificacion.md#prueba-pendiente-en-el-redmi)

### Evaluación de mejoras para LFM3

Se añadieron ocho casos sintéticos en español. Cuatro exigen cálculos y una respuesta JSON. Los otros cuatro comprueban JSON, texto de tres líneas y CSV sin cálculos. La rúbrica JSON registra por separado la validez del formato y la exactitud de los valores. Si el modelo añade delimitadores de Markdown a un objeto correcto, el formato falla y el resultado numérico puede pasar. La salida original queda en cada informe.

El experimento compara el mensaje de sistema de la app con dos variantes locales. La prueba usa LFM3-1.7B Q4_K_M, semilla 1234, dos hilos, contexto máximo de 2.048 y Linux x86-64 con red aislada. Para reproducir el banco de ocho casos, ejecuta los comandos con el JNI compilado y el GGUF ya descargado.

```bash
python3 -m unittest discover -s evaluation -p 'test_*.py'
unshare -Urn -- python3 evaluation/run.py lfm3 /ruta/LFM3.gguf build/inference-host /tmp/lfm3-base.json \
  --cases evaluation/razonamiento-formato-es.jsonl
unshare -Urn -- python3 evaluation/run.py lfm3 /ruta/LFM3.gguf build/inference-host /tmp/lfm3-operaciones.json \
  --cases evaluation/razonamiento-formato-es.jsonl \
  --system-file evaluation/prompts/verificar-operaciones.txt
```

Para evaluar la otra variante, usa `evaluation/prompts/verificar-calculos.txt` con `--system-file`. Ejecuta los mismos comandos sin `--cases` para repetir el banco general de diez casos.

| Mensaje de sistema | Valores matemáticos correctos | JSON con el formato pedido | Casos completos del banco nuevo | Banco general |
| --- | ---: | ---: | ---: | ---: |
| Actual | 1/4 | 2/6 | 4/8 | 4/10 |
| Verificar cálculos | 1/4 | 3/6 | 5/8 | 5/10 |
| Verificar operaciones y límites de JSON | 1/4 | 5/6 | 5/8 | 4/10 |

La tercera variante redujo el Markdown añadido al JSON. Los tres mensajes de sistema resolvieron la misma ecuación y fallaron los otros tres problemas aritméticos. En el banco general, «Verificar cálculos» pasó la comprobación estructural de Fibonacci y la tabla Markdown, y falló la petición de escribir exactamente dos líneas que el mensaje actual sí cumplió. Se completaron 54 inferencias sin errores del motor. Los resultados describen estos casos con una semilla fija. Las variantes se aplicaron mediante el evaluador. [Informes y salidas originales](../evaluation/results/mejora-lfm3)

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

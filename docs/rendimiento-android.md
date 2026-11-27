# Rendimiento de inferencia local en la 0.4.3

La referencia es el APK [0.4.2](https://github.com/operador785b-star/Ai-android-local/releases/tag/v0.4.2) y su motor `a5ac485d56e857d510c6f5f484e07ea98c574d19`. Los campos de etapa y contexto efectivo del diagnóstico del Redmi corresponden a esa versión. Ambas versiones usan llama.cpp v1.1.0 y los mismos GGUF fijados en el catálogo.

## Qué mostró el Redmi

| Modelo o intento | Tokens de entrada | Tokens de salida | Primer fragmento | Total | Generación |
| --- | ---: | ---: | ---: | ---: | ---: |
| LFM3 · 1 | 79 | 12 | 21,551 s | 25,795 s | 2,59 tokens/s |
| LFM3 · 2 | 168 | 24 | 37,746 s | 45,137 s | 3,11 tokens/s |
| LFM3 · 3 | 267 | 128 | 52,829 s | 90,142 s | 3,40 tokens/s |
| Nanbeige importado | 96 | 0 | — | 55,965 s | — |

En la 0.4.2 el JNI abre el GGUF, crea el contexto y evalúa el historial seleccionado en cada generación. Los tiempos del primer fragmento en el teléfono incluyen esas fases juntas. El aumento con los tokens de entrada concuerda con una evaluación cada vez más larga. El pico de 2.866.544 KiB se mide desde el inicio del proceso e incluye el intento de Nanbeige. Por sí solo carece de resolución por modelo.

Nanbeige devolvió `STOPPED` y código nativo 2 mientras evaluaba el prompt con un contexto efectivo de 768. Esa ruta de la 0.4.2 corresponde a la señal de cancelación que recibió el JNI. El registro de esa versión carece de una causa de cancelación, así que el botón y el ciclo de vida siguen siendo explicaciones posibles. El GGUF instalado tenía 2.574.807.904 bytes. Las pruebas reproducibles de esta fase usan el del catálogo, de 2.684.023.968 bytes, y conservan separados ambos resultados.

## Cambios en el motor

- El JNI mantiene el modelo mapeado y el contexto en una sesión serializada. Detecta un archivo reemplazado antes de reutilizarlo y libera sus recursos al cambiar de modelo, en segundo plano, bajo aviso de memoria o al vencer el plazo de inactividad. El administrador libera la sesión antes de borrar el modelo activo.
- El contexto conserva el grupo de hilos de ggml. Así se evita crear y cerrar hilos en cada grafo de cálculo. Los trabajadores esperan sin sondeo activo entre grafos. Un ajuste de hilos distinto reconstruye ese contexto con el número nuevo.
- El motor compara los tokens completos del nuevo prompt con los evaluados, dentro del mismo chat. Cuando el estado sigue siendo válido, evalúa solo el sufijo. Para LFM3 y Qwen3.5 puede restaurar un estado completo guardado en un límite de lote antes del encabezado del asistente. El guardado está acotado a 32 MiB. Nanbeige puede descartar la cola de la caché de atención cuando conserva el prefijo completo. Una divergencia incompatible, un cambio de chat, una ampliación del contexto o una cancelación fuerzan la reconstrucción necesaria.
- La plantilla, la reserva de salida y los criterios de muestreo siguen el contrato de la referencia. El texto del sistema y el del turno actual se preparan una vez al construir candidatos. Kotlin reutiliza buffers para los fragmentos UTF-8. `mmap` sigue activo y Nanbeige conserva desactivado el búfer adicional de reempaquetado para limitar memoria.
- LFM3 y Qwen3.5 asignan el contexto seleccionado. Nanbeige usa el mínimo suficiente en bloques de 256, con un piso de 512, y puede conservar esa asignación si ya es suficiente para el nuevo turno. El diagnóstico registra lo pedido y lo asignado. La capacidad disponible para el historial se decide a partir de los tokens de entrada y la salida reservada.
- El APK ARM64 usa los kernels NEON de la compilación base ARMv8.2-A. Registra si el dispositivo anuncia dot product y FP16 vectorial. Agregar un backend con selección de instrucciones o con KleidiAI exige medir velocidad, memoria y salidas con esos kernels, incluido el coste de reempaquetar pesos. Es una prueba física pendiente. [Variantes ARM de llama.cpp](https://github.com/ggml-org/llama.cpp/blob/171527522610996f5f0acf12334e185156ca8f44/ggml/src/CMakeLists.txt) · [Selección de kernels KleidiAI](https://github.com/ggml-org/llama.cpp/blob/171527522610996f5f0acf12334e185156ca8f44/docs/build.md#runtime-dispatch)

## Perfiles

| Perfil | Contexto y salida iniciales | CPU inicial | Sesión ociosa | Estado recurrente |
| --- | --- | ---: | ---: | --- |
| Económico | 1.024 y 256 | 2 hilos | 30 s | Conserva el estado vivo y omite la copia de respaldo |
| Equilibrado | 2.048 y 512 | 2 hilos | 90 s | Copia acotada cuando el modelo la requiere |
| Máximo rendimiento | 2.048 y 512 | 2 hilos | 180 s | Copia acotada para aprovechar pausas entre turnos |

La base de dos hilos es la configuración con mediciones en el Redmi. La comparación anterior en Linux con cuota de dos CPU también mostró una degradación al usar cuatro hilos. La app admite ajustes manuales de contexto, salida y 2, 3 o 4 hilos. Con ahorro de energía del sistema o estado térmico severo, limita al inicio del turno los hilos efectivos a dos. Los plazos de retención y el contexto económico son políticas ajustables por perfil. Su efecto sobre batería y temperatura queda por medir en el teléfono.

## Banco reproducible

El banco comprueba Python, Kotlin, lógica, matemáticas, conversación en español, Markdown, JSON y tres turnos de memoria. Las pruebas de programación comprueban el texto del código y el patrón esperado. En cada comparación se envían los mismos mensajes, respuestas intermedias guardadas, límites de salida, GGUF, contexto, tamaño de lote y semilla. «Frío» significa que el motor abre un modelo y contexto nuevos en cada turno. El sistema operativo puede conservar páginas del archivo en su caché. «Caliente» usa una sesión abierta para toda la serie, pero cambia el identificador de chat para los casos independientes.

Compila ambos árboles con `scripts/fetch-llama.sh` y el mismo `cmake`, siguiendo las [instrucciones del motor en Linux](fase-inferencia.md#banco-reproducible). Compila la clase `NativeBridge` de la 0.4.3 en **ambos** directorios de compilación. Su modo `baseline` llama al símbolo JNI original. Ejemplo con dos hilos y 2.048 de contexto.

```bash
javac -d /build/042 scripts/NativeBridge.java
javac -d /build/043 scripts/NativeBridge.java
unshare -Urn -- python3 evaluation/performance.py lfm3 /modelos/LFM3.gguf \
  /build/042 /build/043 /tmp/lfm3-rendimiento.json
unshare -Urn -- python3 evaluation/check_cache.py /modelos/LFM3.gguf /build/043
```

También se aceptan `qwen35` y `nanbeige`. La latencia hasta el primer fragmento se mide en el JNI. El evaluador calcula la velocidad comparable con el tiempo entre el primer y último fragmento recibido en Java. Así evita contar como tiempo de generación el cierre del modelo que hacía la 0.4.2. En la app 0.4.3, el JSON también mide entre fragmentos, y registra por separado la carga, creación del contexto, tokenización, procesamiento inicial y salida.

### Medición en Linux x86-64

Se usó un AMD EPYC 9V74 con cuota de dos CPU, red aislada, lotes de 64 y ambos motores compilados con llama.cpp v1.1.0. Los resultados son una ejecución de cada condición con semilla fija. Los tiempos del Redmi están en la sección anterior.

| LFM3 Q4_K_M · diez casos | 0.4.2 por turno | 0.4.3 en frío | 0.4.3 con sesión retenida |
| --- | ---: | ---: | ---: |
| Primer fragmento, mediana | 3,19 s | 2,53 s | 1,82 s |
| Emisión, mediana de casos con al menos dos fragmentos | 23,63 fragmentos/s | 25,34 fragmentos/s | 24,33 fragmentos/s |
| Casos aprobados | 5/10 | 5/10 | 5/10 |

En los turnos 2 y 3 de memoria, la 0.4.3 reutilizó 127 de 166 y 170 de 207 tokens. El primer fragmento pasó de 3,51 a 0,75 s y de 3,93 a 0,88 s, respectivamente. En frío, la 0.4.3 separó 650 ms de carga y 2.924 ms de procesamiento en el turno 2. En caliente, la carga fue de 0 ms y el procesamiento fue de 745 ms. La tokenización se midió en 0 ms con resolución de milisegundos. La salida textual de LFM3 cambió en uno de diez casos calientes, y ese caso falló el mismo criterio de formato en ambas versiones. La latencia en caliente mostró el mayor cambio observado. La variación en emisión se registra como medida del host y de esta ejecución.

La prueba de caché con LFM3 restauró 64 tokens tras cambiar el texto de la respuesta previa. También validó ocho cambios de chat, una cancelación y una nueva respuesta con el modelo retenido y el contexto reconstruido después de detener.

| Serie final de tres turnos | Primer fragmento mediano, 0.4.2 | 0.4.3 en frío | 0.4.3 caliente | Tokens reutilizados en los turnos 2 y 3 | Casos aprobados por versión |
| --- | ---: | ---: | ---: | ---: | ---: |
| Qwen3.5-2B | 3,24 s | 3,25 s | 1,56 s | 64 y 128 | 3/3 |
| Nanbeige 4.2 3B del catálogo | 14,75 s | 13,84 s | 5,25 s | 152 y 206 | 3/3 |

Una iteración intermedia que ya reutilizaba el contexto y aún creaba los trabajadores por grafo pasó el banco completo con Qwen3.5 (10/10) y Nanbeige (8/10) en las tres condiciones. La tabla usa la compilación final con trabajadores retenidos. Para aislar esas series, guarda las últimas tres filas del banco en un archivo y pásalo con `--cases`. [Informes con salidas y métricas](../evaluation/results/rendimiento-043)

Con Nanbeige, la mediana de emisión de esos tres turnos pasó de 5,17 a 3,89 fragmentos/s en caliente. En un control adicional con un párrafo de 75 palabras y 141 fragmentos en todas las condiciones, la velocidad fue 5,06 fragmentos/s en la 0.4.2, 5,44 en frío y 4,65 en caliente en la 0.4.3. El primer fragmento de ese párrafo llegó en 10,18 s con la 0.4.2 y 8,15 s en caliente con la 0.4.3. La menor latencia inicial y la menor tasa posterior en caliente se observaron ambas en Linux. Repite ese control con `--cases evaluation/performance-emision-es.jsonl`.

GitHub Actions compiló ambos APK ARM64 y ejecutó 16 pruebas unitarias con cero fallos. Se verificaron las firmas y el contenido de ambos APK publicados. Cada modelo se instala por separado. [Compilación y pruebas](https://github.com/operador785b-star/Ai-android-local/actions/runs/45989826453) · [Distribución directa](https://github.com/operador785b-star/Ai-android-local/releases/tag/v0.4.3)

## Cómo comparar en el Redmi

1. Instala las vistas previas 0.4.2 y 0.4.3 en paralelo. Elige el mismo GGUF del catálogo en ambas. Sus datos son independientes.
2. Configura dos hilos, 2.048 tokens de contexto y 512 de salida en ambas. Usa Equilibrado en la 0.4.3. Haz las pruebas con una temperatura inicial parecida y el mismo modo de ahorro de energía.
3. Para medir arranque frío, fuerza la detención desde **Información de la app**, vuelve a abrirla y envía «Hola» en un chat nuevo. Repite tres veces en cada APK. Anota el primer fragmento y el tiempo total.
4. Para una sesión caliente, abre un chat nuevo y envía seguidos los tres mensajes `memoria-1`, `memoria-2` y `memoria-3` del banco. Repite en ambas versiones y comprueba que los tamaños de entrada sean comparables. La app usa las respuestas reales de cada charla. El banco Linux fija las respuestas intermedias para que las entradas sean idénticas. [Preguntas del banco](../evaluation/performance-es.jsonl)
5. En la 0.4.3 ve a **Ajustes → Diagnóstico local → Guardar diagnóstico JSON** o usa **Copiar diagnóstico JSON**. Busca `carga_modelo_ms`, `procesamiento_prompt_ms`, `tokens_prompt_reutilizados`, `contexto_motor` y `hilos_efectivos`. `rss_proceso_kib` es la lectura del proceso al final y `pico_rss_proceso_kib` es su máximo desde el inicio. El informe omite mensajes y respuestas.
6. Para probar cancelación, detén un turno durante **Procesando tu mensaje…** y envía otro. El JSON distingue `BOTON_DETENER`, `CICLO_DE_VIDA`, `LIMITE_PRIMER_FRAGMENTO` y `PROCESO_INTERRUMPIDO` cuando dispone del dato. Repite también con modo avión y Wi-Fi apagada.

Quedan por medir en el Redmi la velocidad de la 0.4.3, el efecto térmico y de batería de cada perfil, la recuperación tras presión de memoria y el comportamiento del GGUF de Nanbeige importado de 2,57 GB. Los APK de prueba 0.4.2 y 0.4.3 tienen firmas diferentes. La variante de vista previa usa un identificador independiente y permite instalar la nueva versión al lado de la anterior. La actualización en el mismo paquete necesita una compilación firmada con el certificado de la instalación existente.

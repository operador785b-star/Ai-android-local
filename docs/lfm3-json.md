# Modo JSON de LFM3 en la 0.4.5

Con LFM3-1.7B activo, escribe `/json` al inicio del mensaje e indica las claves y los datos que necesitas. La app interpreta el comando y guarda la petición en el historial. La gramática del motor admite un objeto raíz con valores de texto, números, booleanos, listas y otros objetos. LFM3 genera el contenido localmente con los pesos Q4_K_M del catálogo.

```text
/json Devuelve las claves ciudad con valor Lima y dias con el entero 3.
```

La restricción controla la sintaxis. El modelo sigue eligiendo las claves y los valores. Una respuesta detenida o cortada por el límite de salida puede quedar incompleta. Los mensajes normales siguen usando el muestreador del chat. El diagnóstico registra `formato: JSON` cuando se usa el comando y prescinde del texto de la conversación.

El motor comprueba primero el token elegido y aplica la gramática al vocabulario completo cuando debe sustituirlo. Esto evita volver a filtrar todos los tokens en cada paso. [Muestreo con gramática de llama.cpp](https://github.com/ggml-org/llama.cpp/blob/171527522610996f5f0acf12334e185156ca8f44/common/sampling.cpp#L614)

## Reproducción en Linux

Construye el JNI y compila `scripts/NativeBridge.java` con el procedimiento del [banco de inferencia](fase-inferencia.md#banco-reproducible). La base de comparación es el muestreador de la 0.4.4. Para reproducirla exactamente, usa el árbol `77cad209b64373528492bfe5a8ace8ed17ea5b9b` para compilar su biblioteca y la clase `NativeBridge` actual para ejecutar el banco en ambos directorios.

```bash
unshare -Urn -- python3 evaluation/run.py lfm3 /modelos/LFM3.gguf /build/044 /tmp/lfm3-base.json \
  --cases evaluation/razonamiento-formato-es.jsonl
unshare -Urn -- python3 evaluation/run.py lfm3 /modelos/LFM3.gguf /build/045 /tmp/lfm3-json.json \
  --cases evaluation/razonamiento-formato-es.jsonl --json-object
unshare -Urn -- python3 evaluation/run.py lfm3 /modelos/LFM3.gguf /build/045 /tmp/lfm3-anidado.json \
  --cases evaluation/lfm-json-anidado-es.jsonl --json-object
```

La rúbrica registra por separado la sintaxis con las claves y tipos pedidos, la exactitud de los valores y la aprobación de todo el caso. La prueba usa un contexto de 2.048, dos hilos y semilla 1234. Las métricas del host describen Linux x86-64. El tiempo de respuesta en el Redmi se mide aparte.

## Resultados verificados

| Configuración | JSON con las claves y tipos pedidos | Problemas matemáticos con valores correctos | Banco completo | Mediana de emisión en los seis casos JSON |
| --- | ---: | ---: | ---: | ---: |
| Chat normal 0.4.4 | 2/6 | 1/4 | 4/8 | 21,59 tokens/s |
| Prototipo con filtro completo en cada token | 6/6 | 1/4 | 5/8 | 17,66 tokens/s |
| Modo `/json` de la 0.4.5 | 6/6 | 1/4 | 5/8 | 21,17 tokens/s |

La ecuación era correcta antes del cambio. El modo JSON quitó el delimitador adicional de Markdown y permitió aprobar el caso completo. Los dos controles nuevos entregaron los valores esperados, una lista de objetos y una cadena con comillas escapadas y un número negativo. En otra prueba JNI se generó un objeto completo, se volvió al chat normal en la misma sesión y se comprobó que una salida cortada se marca como limitada. [Salidas y métricas originales](../evaluation/results/lfm3-json-045)

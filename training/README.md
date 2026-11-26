# Personalizar Qwen3.5-2B

La carpeta deja preparada la segunda etapa para una GPU NVIDIA externa. En Codex Cloud se comprobaron la sintaxis y la preparación reproducible de datos. El entrenamiento, la evaluación con adaptador, la exportación y la cuantización de un modelo personalizado siguen pendientes de ejecución.

## Entorno

Usa Linux x86-64, Python 3.11 y una GPU con BF16. Una L4 de 24 GB o una A100 permite ensayar la configuración inicial. El consumo real depende de la longitud de los ejemplos. Para fusionar los pesos y convertir a GGUF deja espacio en disco para el modelo original, el fusionado y un archivo intermedio F16.

```bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install torch==2.8.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r training/requirements.txt
python -m pip check
```

## Datos y entrenamiento

Prepara un JSONL con `instruction`, `response` y `task`. Usa las categorías `codigo`, `matematicas`, `razonamiento` o `conversacion`. Conserva por separado los casos de evaluación final. Usa ejemplos que tengas derecho a utilizar y elimina datos personales antes de llevarlos a la GPU externa.

```bash
python training/data.py datos.jsonl training/runs/datos --seed 42
python training/train.py \
  --train training/runs/datos/train.jsonl \
  --validation training/runs/datos/validation.jsonl \
  --output training/runs/ajuste-01 --mode qlora --seed 42
```

QLoRA usa NF4 para el modelo base y entrena adaptadores sobre la rama de texto. Usa `--mode lora` para entrenar sobre BF16. La pérdida se calcula sobre la respuesta. Se registran la revisión base, las sumas de los datos, los parámetros y las métricas. Los cuatro ejemplos incluidos sirven para probar el formato.

## Evaluar, exportar e importar

```bash
python training/evaluate.py \
  --cases evaluation.jsonl \
  --adapter training/runs/ajuste-01/adapter \
  --output training/runs/ajuste-01/evaluacion.json
python training/export.py training/runs/ajuste-01/adapter training/runs/ajuste-01/merged
training/quantize.sh training/runs/ajuste-01/merged training/runs/ajuste-01/android training/runs/build-llama
```

Comprueba los resultados por tarea y revisa manualmente las salidas. La evaluación automática compara el modelo oficial con el adaptador mediante coincidencias exactas o de texto. La exportación fusiona el adaptador sobre el original sin cuantizar. El paso final genera un GGUF Q4_K_M con su SHA-256 y los datos de procedencia.

Para una comprobación rápida en Linux usa `scripts/smoke-native.sh RUTA_GGUF DIRECTORIO_BUILD`. En el Redmi abre **Modelos locales → Importar GGUF** y pega el SHA-256 de `SHA256SUMS`. Prueba el archivo cuantizado en el teléfono antes de seleccionarlo para uso habitual.

[Modelo base](https://huggingface.co/Qwen/Qwen3.5-2B) · [Qwen3.5 en Transformers](https://huggingface.co/docs/transformers/en/model_doc/qwen3_5) · [PEFT](https://huggingface.co/docs/peft/index) · [PyTorch con CUDA](https://pytorch.org/get-started/previous-versions/)

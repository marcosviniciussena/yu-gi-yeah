FROM python:3.13.7

WORKDIR /app

COPY . .

CMD ["python", "servidor.py"]
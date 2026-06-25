FROM python:3.10-alpine
WORKDIR /workspace
RUN apk add --no-cache bash make
CMD ["sh"]

# 🧩 Локальная интеграция с WooCommerce (`paint.local`)

## 1. Цель
При локальной разработке Java-сервиса (`Lavka Sync` / `Woo API client`) запросы идут на  
`https://paint.local/wp-json/...` (сайт из LocalWP).  
Так как Local генерирует **самоподписанный SSL-сертификат**, Java из коробки ему не доверяет.  
Чтобы устранить `PKIX path building failed`, нужно добавить этот сертификат в доверенные.

---

## 2. Импорт сертификата LocalWP в truststore Java

### 🔍 Проверить текущий Java-путь:
```bash
/usr/libexec/java_home
```
или прямо из приложения:
```java
System.out.println(System.getProperty("java.home"));
```

### 🧾 Импорт команды:
```bash
sudo keytool -importcert   -alias paintlocal   -file "$HOME/Library/Application Support/Local/run/router/nginx/certs/paint.local.crt"   -keystore "$(/usr/libexec/java_home)/lib/security/cacerts"
```

> 💡 Пароль по умолчанию: `changeit`

После команды появится вопрос `Trust this certificate? [no]:` — введи `yes`.

---

## 3. Проверка установки
```bash
sudo keytool -list -keystore "$(/usr/libexec/java_home)/lib/security/cacerts" | grep paintlocal
```
Если видишь строку вроде  
```
paintlocal, 17 окт. 2025 г., trustedCertEntry,
```
— значит сертификат успешно добавлен.

---

## 4. Перезапуск и тест
Перезапусти Spring Boot-приложение и проверь любой запрос к Woo API:
```
GET https://paint.local/wp-json/wc/v3/products/categories?per_page=1
```
В логах не должно быть ошибок `PKIX path building failed` или `unable to find valid certification path`.

---

## 5. Удаление при необходимости
Если нужно удалить сертификат:
```bash
sudo keytool -delete -alias paintlocal -keystore "$(/usr/libexec/java_home)/lib/security/cacerts"
```

---

## 6. Альтернатива: отдельный truststore для DEV
Если не хочешь трогать системный `cacerts`, можно сделать собственный файл:
```bash
keytool -importcert   -alias paintlocal   -file "$HOME/Library/Application Support/Local/run/router/nginx/certs/paint.local.crt"   -keystore ./dev-truststore.jks   -storepass changeit
```

и запускать Java так:
```bash
java -Djavax.net.ssl.trustStore=./dev-truststore.jks      -Djavax.net.ssl.trustStorePassword=changeit      -jar app.jar
```

---

## 7. Troubleshooting

| Проблема | Решение |
|-----------|----------|
| `PKIX path building failed` | Импортируй сертификат в актуальную JRE (см. `java.home`). |
| `401 Unauthorized` | Проверь `woocommerce.key` и `woocommerce.secret` в `.env`. |
| `I/O error on GET request` | Убедись, что URL начинается с `https://paint.local`. |
| `Invalid certificate` | Пересоздай сертификат в LocalWP (`Trust` → `Re-generate`). |

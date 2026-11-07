# Herdr Android: Git и готовность к F-Droid

Проверено 30 октября 2026 года. Это отчёт о подготовке, не подтверждение приёма приложения в каталог.

## Что выполнено

- Создан отдельный локальный Git-репозиторий в `android/`, ветка `master`.
- В репозиторий включены Kotlin/Compose-клиент, ресурсы, Gradle Wrapper, тесты и документация. Соседний сервер `../herdr` остаётся отдельным проектом.
- `.gitignore` исключает `dist/`, APK/AAB, сборки, кеши, IDE, `local.properties`, `.env` и файлы signing-keystore.
- Автор локального коммита: `pkg-tool <175218816+pkg-tool@users.noreply.github.com>`. ID аккаунта проверен через публичный GitHub API. Глобальные Git-настройки не менялись.
- GitHub-репозиторий, push, релизный тег и заявка F-Droid не создавались: изменения закоммичены локально по запросу. Remote не задан.

## SSH-доступ к GitHub

В `~/.ssh/config` найден alias `gpkg` (блок продублирован с одинаковыми значениями):

```sshconfig
Host gpkg
    HostName github.com
    User git
    IdentityFile ~/.ssh/id_ed25519_gh_pkgtool
```

Проверка `ssh -T -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes gpkg` вернула:

```text
Hi pkg-tool! You've successfully authenticated, but GitHub does not provide shell access.
```

Код возврата 1 здесь нормален: GitHub разрешает Git-транспорт, но не shell. Содержимое приватного ключа не читалось и в репозиторий не копировалось. `IdentitiesOnly=yes` задан в локальном `core.sshCommand`, чтобы не предлагать GitHub посторонние ключи агента.

Для будущего репозитория с выбранным именем SSH URL будет иметь вид `git@gpkg:pkg-tool/<repository>.git`. SSH-аутентификация сама по себе не создаёт репозиторий и не подтверждает доступ к GitHub REST API.

## Проверенная сборка

Команда из корня клиентского проекта:

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleRelease
```

Результат: **BUILD SUCCESSFUL**, получен `app/build/outputs/apk/release/app-release-unsigned.apk`.

| Параметр | Проверенное значение |
| --- | --- |
| Application ID | `dev.herdr.android` |
| Version name / code | `0.1.0` / `1` |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Android Gradle Plugin | 9.1.1 |
| Gradle / Kotlin plugins | 9.3.1 / 2.2.10 |
| JDK при проверке | OpenJDK 21.0.11, Homebrew |
| APK | unsigned release, без флага debuggable |

Проверены APK через `aapt` и отсутствие подписи через `apksigner`. Release signing не настроен. Это локальная сборка с имеющимся кешем зависимостей, не сборка в инфраструктуре F-Droid и не доказательство воспроизводимости.

SHA-256 локального `gradle-wrapper.jar` совпал с [официальной контрольной суммой Gradle 9.3.1](https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar.sha256):

```text
b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13
```

`distributionSha256Sum` пока не закреплён в wrapper properties. Для используемого `-all.zip` [официальная сумма](https://services.gradle.org/distributions/gradle-9.3.1-all.zip.sha256): `17f277867f6914d61b1aa02efab1ba7bb439ad652ca485cd8ca6842fccec6e43`.

## Что требуется для основного каталога F-Droid

F-Droid требует публичные полные исходники, свободную лицензию, допустимые зависимости и возможность собрать приложение свободными инструментами. Приложение должно быть полезным и поддерживаемым. Текущая политика также указывает минимум три месяца активной разработки, с исключениями, например при подтверждённом наличии пользователей; применимость исключения оценивают сопровождающие. Имя, ресурсы и Application ID не должны нарушать чужие права. [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)

Для подачи нужны метаданные приложения и рецепт сборки в `fdroiddata`, привязанный к версии исходников. В upstream рекомендуется структура `fastlane/metadata/android/en-US/`: описания, иконка, скриншоты и changelog по versionCode. Релизы следует отмечать согласованными Git-тегами. [Quick Start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)

Заявка подаётся через MR в `fdroiddata`; новичкам также предлагается Request for Packaging. Перед MR проверяют рецепт и CI. Это процесс на GitLab, поэтому найденного GitHub SSH-доступа для самой подачи недостаточно. [CONTRIBUTING](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)

## Что остаётся у нашего проекта

1. **Лицензия клиента оформлена: Apache-2.0.** По решению владельца добавлен полный текст в `LICENSE`, область применения указана в README. Этот блокер снят; аудит лицензий и обязательных уведомлений сторонних компонентов остаётся отдельным этапом.
2. **Публичного upstream ещё нет.** Создать репозиторий аккаунта `pkg-tool`, опубликовать исходники и настроить issue tracker. Предлагаемое имя — `herdr-android`, но оно ещё не занято нами и доступность имени не проверялась.
3. **Application ID и название требуют решения до первого релиза.** Сейчас используется `dev.herdr.android`; владение соответствующим доменом и разрешение на бренд Herdr не проверены. При необходимости выбрать собственный идентификатор заранее: его смена создаёт отдельное приложение.
4. **Метаданных F-Droid и релизного тега нет.** Подготовить описания, чистые демонстрационные скриншоты, иконку и changelog; текущие `dist/`-скриншоты не публиковать без просмотра на личные данные. Затем пометить утверждённый коммит тегом версии.
5. **Рецепт F-Droid ещё не проверен.** `fdroidserver` не установлен. Требуется проверить поддержку AGP 9.1.1 / Gradle 9.3.1 / SDK 36 в build environment, выполнить проверки metadata и `fdroid build`. Пересборка из чистого checkout на F-Droid не выполнялась.
6. **Нужен выбор подписи.** Варианты описаны ниже. Закрытые signing-ключи не должны попадать в Git.

По исходникам и read-only ревью Claude явных GMS/Firebase/рекламных SDK нет. Прямые зависимости — AndroidX/Compose, kotlinx, JSch и Bouncy Castle из Google Maven/Maven Central; это выглядит подходящей основой. Полный аудит транзитивных лицензий и проверка F-Droid scanner ещё не выполнены.

Клиент подключается к заданному пользователем SSH-серверу, а сервер Herdr доступен с открытыми исходниками. Tailscale не встроен и не обязателен. Поэтому обязательной привязки к конкретному сетевому провайдеру по коду не видно. Итоговую классификацию, включая отображаемые предложения сторонних coding agents, определяют при ревью. [Anti-Features](https://f-droid.org/docs/Anti-Features/)

## Подпись и обновления

- Обычная схема: F-Droid собирает исходники и подписывает своим ключом. Наш unsigned release APK показывает, что локальный путь сборки существует; собственный release-ключ не является обязательным условием этой схемы.
- Если нужны одинаково подписанные APK из GitHub Releases и F-Droid, выбрать собственный стабильный release-ключ и проверить reproducible builds. Для проверки upstream APK рецепт использует `Binaries`/`binary` и `AllowedAPKSigningKeys`. Совпадение сборок ещё не проверено. [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)
- Воспроизводимые сборки рекомендованы, но не являются общим обязательным условием включения. Выбирать схему подписи лучше до первой публикации. [Quick Start: reproducible builds](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/#setup-reproducible-build)
- На Pixel установлена debug-сборка. Обычный APK с другой подписью не обновит её поверх. Для перехода потребуется отдельный application ID либо переустановка; при удалении приложения локальные профили и Keystore-секреты будут потеряны. Миграция данных не реализована, приложение на телефоне в рамках этого задания не удалялось.

Основная неопределённость сейчас — права на название и сторонние компоненты, публичный upstream и успешная сборка по рецепту F-Droid. Локальная release-сборка и найденный SSH-доступ этих этапов не заменяют.

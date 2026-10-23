# Модель углов страницы (doccornernet_lean.onnx)

`doccornernet_lean.onnx` — DocCornerNet LEAN, сжатая по каналам SimCC-сеть углов документа
(архитектура — DocCornerNet, https://github.com/mapo80/DocCornerNet-CoordClass, MIT). Файл взят
из проекта scanic: https://raw.githubusercontent.com/marquaye/scanic/main/scripts/ml-spike/model/doccornernet_lean.onnx
(sha256 `4882f465e4fc57b6b1ade4d50efe0f245fd73e5bb294f74f2d97256ca4fcf48c`, 1,8 МБ).

Лицензия — MIT (scanic, © 2025 marquaye; DocCornerNet — MIT).

Вход — `[1, 224, 224, 3]` float32, RGB / 255 и нормировка ImageNet. Выход — `coords` `[1, 8]`
(четыре угла 0..1: верх-лево, верх-право, низ-право, низ-лево) и `score_logit` `[1, 1]`
(сигмоида — есть ли на кадре документ).

Чем Point её зовёт: `executors/src/shared/kotlin/com/point/vision/LearnedCorners.kt` — второй ход
поиска листа, когда свой (`PageVision`) страницу не нашёл (#1044). Исполняется OpenCV DNN на самом
устройстве — телефоне или компьютере: снимок никуда не уходит.

import os
import csv
import copy
import itertools
import time
import cv2 as cv
import numpy as np
import pandas as pd
import tensorflow as tf
import mediapipe as mp
import tkinter as tk

from collections import deque
from PIL import Image, ImageTk
from tensorflow import keras
from keras.layers import Reshape, GRU, Dense

reference_points = 21
point_array_size = 45
dimension_count = 2


def train_model():
    try:
        df = pd.read_csv('model/train_dataset.csv')
        X_train = df.iloc[:, 1:]
        y_train = df.iloc[:, 0]
    except FileNotFoundError:
        log_text.insert(tk.END, "Ошибка, обучающий датасет не создан.\n")
        application.update_idletasks()
        return

    if len(y_train.unique()) != 3:
        log_text.insert(tk.END, "Ошибка, в датасете должно быть три класса.\n")
        application.update_idletasks()
        return

    y_train_cat = keras.utils.to_categorical(y_train, 3)

    model = keras.models.Sequential([
        Reshape((point_array_size, reference_points * dimension_count),
                input_shape=(reference_points * point_array_size * dimension_count,)),
        GRU(100, input_shape=(reference_points * point_array_size, dimension_count)),
        Dense(100, activation='relu'),
        Dense(10, activation='relu'),
        Dense(3, activation='softmax')
    ])
    model.compile(optimizer='adam', loss='categorical_crossentropy', metrics=['accuracy'])

    log_text.insert(tk.END, "Обучение нейронной сети...\n")
    application.update_idletasks()

    es_callback = tf.keras.callbacks.EarlyStopping(monitor='loss', patience=10, verbose=1)
    model.fit(X_train, y_train_cat, epochs=50, batch_size=32, callbacks=[es_callback])

    # Преобразование модели в формат TF Lite (Квантизация модели)
    run_model = tf.function(lambda x: model(x))
    func = run_model.get_concrete_function(tf.TensorSpec(
        (1, reference_points * point_array_size * dimension_count), model.inputs[0].dtype))
    model.save("model", save_format="tf", signatures=func)
    converter = tf.lite.TFLiteConverter.from_saved_model("model")
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float64]
    tflite_quantized_model = converter.convert()
    open('model/model.tflite', 'wb').write(tflite_quantized_model)

    log_text.insert(tk.END, "Обучение нейронной сети завершено.\n")
    application.update_idletasks()


def run_camera():
    # Инициализация объекта видеозахвата (Веб-камеры устройства)
    camera = cv.VideoCapture(0)
    camera.set(cv.CAP_PROP_FRAME_WIDTH, 640)
    camera.set(cv.CAP_PROP_FRAME_HEIGHT, 480)

    # Создание объекта hand_detector для отслеживания кисти руки
    mp_hands = mp.solutions.hands
    hand_detector = mp_hands.Hands(
        max_num_hands=1,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5
    )

    # Инициализация массивов для хранения последовательностей точек
    coordinate_array = deque(maxlen=45)
    number = 99

    def number_1(event):
        nonlocal number
        number = 1

    def number_2(event):
        nonlocal number
        number = 2

    def number_3(event):
        nonlocal number
        number = 3

    # Привязка клавиш клавиатуры для сохраненения жестов каждого класса
    application.bind('1', number_1)
    application.bind('2', number_2)
    application.bind('3', number_3)

    def update_camera():
        nonlocal camera, hand_detector, coordinate_array, number
        start_time = time.time()
        # Получение изображения с камеры
        ret, frame = camera.read()
        frame = cv.cvtColor(frame, cv.COLOR_BGR2RGB)
        frame = cv.flip(frame, 1)
        # Предсказание координат опорных точек на изображении
        results = hand_detector.process(frame)
        if results.multi_hand_landmarks is not None:
            points = reverse_normalization(frame, results.multi_hand_landmarks[0])
            coordinates = \
                [points[0], points[1], points[2], points[3],
                points[4], points[5], points[6], points[7],
                points[8], points[9], points[10], points[11],
                points[12], points[13], points[14], points[15],
                points[16], points[17], points[18], points[19],
                 points[20]]
            coordinate_array.append(coordinates)
            save_to_csv(number, coordinate_array)
            number = 99
            # Отрисовка опорных точек на кадре
            frame = draw_reference_points(frame, points)
        else:
            coordinate_array.clear()

        # Преобразование кадра в объект Image
        img = Image.fromarray(frame)
        imgtk = ImageTk.PhotoImage(image=img)

        # Обновление изображения на метке label новым кадром
        label.imgtk = imgtk
        label.configure(image=imgtk)

        # Расчет прошедшего времени и ожидание для достижения нужной частоты кадров
        elapsed_time = time.time() - start_time
        target_time = 1 / 15
        sleep_time = target_time - elapsed_time
        if sleep_time > 0:
            time.sleep(sleep_time)
        application.after(1, update_camera)

    update_camera()


def reverse_normalization(image, hand_landmarks):
    width, height = image.shape[1], image.shape[0]
    landmark_point = []
    for index, landmark in enumerate(hand_landmarks.landmark):
        x = min(int(landmark.x * width), width - 1)
        y = min(int(landmark.y * height), height - 1)
        landmark_point.append([x, y])
    return landmark_point


def normalize(points):
    points_copy = copy.deepcopy(points)
    x_first, y_first = points[0][0][0], points[0][0][1]
    for index, point_set in enumerate(points_copy):
        for point_index, point in enumerate(point_set):
            points_copy[index][point_index][0] = (points_copy[index][point_index][0] - x_first) / 640
            points_copy[index][point_index][1] = (points_copy[index][point_index][1] - y_first) / 480

    points_copy = list(itertools.chain.from_iterable(points_copy))
    return points_copy


def save_to_csv(number, coordinate_array):
    flag_1, flag_2 = 0, 0
    if train_dataset_flag.get() == 0 and test_dataset_flag.get() == 0:
        return
    if train_dataset_flag.get() == 1 and (1 <= number <= 3):
        normalized = np.array(normalize(coordinate_array)).flatten()
        csv_path = 'model/train_dataset.csv'
        with open(csv_path, 'a', newline="") as f:
            writer = csv.writer(f)
            if len(normalized) == 2 * 21 * 45:
                writer.writerow([number - 1, *normalized])
                flag_1 = 1
    if test_dataset_flag.get() == 1 and (1 <= number <= 3):
        csv_path = 'model/test_dataset.csv'
        normalized = np.array(normalize(coordinate_array)).flatten()
        with open(csv_path, 'a', newline="") as f:
            writer = csv.writer(f)
            if len(normalized) == 2 * 21 * 45:
                writer.writerow([number - 1, *normalized])
                flag_2 = 1
    if number == 1 and (flag_1 == 1 or flag_2 == 1):
        log_text.insert(tk.END, "Наблюдение сохранено под меткой класса 1.\n")
        application.update_idletasks()
    if number == 2 and (flag_1 == 1 or flag_2 == 1):
        log_text.insert(tk.END, "Наблюдение сохранено под меткой класса 2.\n")
        application.update_idletasks()
    if number == 3 and (flag_1 == 1 or flag_2 == 1):
        log_text.insert(tk.END, "Наблюдение сохранено под меткой класса 3.\n")
        application.update_idletasks()
    return


def draw_reference_points(image, point):
    cv.circle(image, (point[0][0], point[0][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[1][0], point[1][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[2][0], point[2][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[3][0], point[3][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[4][0], point[4][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[5][0], point[5][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[6][0], point[6][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[7][0], point[7][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[8][0], point[8][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[9][0], point[9][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[10][0], point[10][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[11][0], point[11][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[12][0], point[12][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[13][0], point[13][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[14][0], point[14][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[15][0], point[15][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[16][0], point[16][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[17][0], point[17][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[18][0], point[18][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[19][0], point[19][1]), 5, (255, 255, 255), -1)
    cv.circle(image, (point[20][0], point[20][1]), 5, (255, 255, 255), -1)
    return image


def test_model():
    try:
        df = pd.read_csv('model/test_dataset.csv')
        X_test = df.iloc[:, 1:].values
        y_test = df.iloc[:, 0].values
    except FileNotFoundError:
        log_text.insert(tk.END, "Ошибка, тестовый датасет не создан.\n")
        application.update_idletasks()
        return

    y_test_cat = keras.utils.to_categorical(y_test, 3)

    try:
        interpreter = tf.lite.Interpreter(model_path="model/model.tflite", num_threads=1)
    except ValueError:
        log_text.insert(tk.END, "Ошибка, файл нейронной сети отсуствует.\n")
        application.update_idletasks()
        return

    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    true_positive = 0

    for i in range(len(X_test)):
        input_data = np.array([X_test[i]], dtype=np.float32)
        interpreter.set_tensor(input_details[0]['index'], input_data)
        interpreter.invoke()
        output_data = interpreter.get_tensor(output_details[0]['index'])
        result_index = np.argmax(np.squeeze(output_data))

        if np.squeeze(output_data)[result_index] < 0.9:
            result_index = -1

        if result_index == np.argmax(y_test_cat[i]):
            true_positive += 1

        log_text.insert(tk.END, f"{i + 1}) Prediction: {result_index}, "
                                f"Truth: {np.argmax(y_test_cat[i])}\n")

    accuracy = true_positive / len(X_test) * 100
    log_text.insert(tk.END, f"Accuracy: {accuracy:.2f}%\n")
    application.update_idletasks()


def delete_train_dataset():
    try:
        os.remove('model/train_dataset.csv')
    except FileNotFoundError:
        log_text.insert(tk.END, "Ошибка, обучающий датасет не создан.\n")
        application.update_idletasks()
    except Exception as e:
        print(f"Ошибка при удалении обучающего датасета: {e}")


def delete_test_dataset():
    try:
        os.remove('model/test_dataset.csv')
    except FileNotFoundError:
        log_text.insert(tk.END, "Ошибка, тестовый датасет не создан.\n")
        application.update_idletasks()
    except Exception as e:
        print(f"Ошибка при удалении тестового датасета: {e}")


# Создание основного окна программы
application = tk.Tk()
application.title("Gesture Locker Learner")

# Создание метки для отображения изображения
label = tk.Label(application)
label.pack()

# Создание контейнера для кнопок обучения и тестирования
button_train = tk.Button(
    text="Обучить нейронную сеть",
    command=train_model,
    font=("Helvetica", 12),
    bg="green",
    fg="white",
    width=25
)
button_train.pack(pady=5)
button_test = tk.Button(
    text="Тестировать нейронную сеть",
    command=test_model,
    font=("Helvetica", 12),
    bg="blue",
    fg="white",
    width=25
)
button_test.pack(pady=5)

# Создание контейнера для кнопок удаления датасетов
button_delete_train = tk.Button(
    text="Удалить обучающий датасет",
    command=delete_train_dataset,
    font=("Helvetica", 12),
    bg="red",
    fg="white",
    width=25
)
button_delete_train.pack(pady=5)
button_delete_test = tk.Button(
    text="Удалить тестовый датасет",
    command=delete_test_dataset,
    font=("Helvetica", 12),
    bg="red",
    fg="white",
    width=25
)
button_delete_test.pack(pady=5)

# Создание чек-боксов
train_dataset_flag = tk.IntVar()
test_dataset_flag = tk.IntVar()
checkbox_frame = tk.Frame(application)
checkbox_frame.pack()
checkbutton1 = tk.Checkbutton(
    checkbox_frame,
    text="Запись обучающего датасета",
    variable=train_dataset_flag,
    font=("Helvetica", 10)
)
checkbutton1.pack(anchor="w")
checkbutton2 = tk.Checkbutton(
    checkbox_frame,
    text="Запись тестового датасета",
    variable=test_dataset_flag,
    font=("Helvetica", 10)
)
checkbutton2.pack(anchor="w")

# Создание текстового окна логов
log_text = tk.Text(application, height=5, width=50)
log_text.pack(pady=5)

# Запуск функции отображения камеры и самого приложения
application.after(10, run_camera)
application.mainloop()

# Overview
This is a software RS-232 remote aimed towards VCRs that don't have proper RS-422 connection

# Features

* **Automatic port opening & closing**
* **Built-in timer function**
* **5 user-defined buttons + 1 checkbox**
* **User-editable commands for all buttons**

# Setup and usage

This remote's default layout uses _Sony UVW-1200_ Betacam SP VCR command set. 

<img width="844" height="482" alt="uvw1200" src="https://github.com/user-attachments/assets/0c51ad49-b7eb-4ba0-96a7-ab3c1a3151f0" />

User editable settings are stored in **config.properties**

The default button order is:

<img width="841" height="429" alt="def" src="https://github.com/user-attachments/assets/66077ba7-b383-4577-8425-29d77c1f62b4" />


## Customization

In order to use customize this program you need to:

1. **Set up user buttons:**
   * _buttonX_name_ is a button **display name** (where X - is a button number)
   * _buttonX_cmd_ is a **button command** (where X - is a button number)

2. **Set up user checkbox:**
    * user checkbox **asserts RTS**
    * _optional1_name_ is a checkbox **display name**
    * _optional1_engaged_ set up checkbox status _(checked/unchecked)_ be default

3. **Redefine basic commands**

## Timer setup

This remote has a resettable timer which allows for delayed _STOP_ command. It allows to stop a VCR remotely after a fixed time interval and should be used with a timer in capture software to end the recording around that point. 

Timer has a **default value of 10 minutes** and displays remaining time in **_H:mm_ format**.

Timer can be set in range between **1** and **360** **minutes** with 1 minute increment. You can enter the number manually or by **scrolling a mouse wheel**.

<img width="844" height="482" alt="timer" src="https://github.com/user-attachments/assets/d6a35f2f-6104-446d-ac42-cb4a6942bd3c" />

## Error handling

Selected port will be opened **automatically** if you press any button. You will get a message if port can't be opened, there's an issue with output stream, etc.

For example:

<img width="632" height="311" alt="err" src="https://github.com/user-attachments/assets/fadab213-a1f7-4614-b5ed-6eb3606e530e" />


# Overview
This is a software RS-232 remote aimed towards VCRs that don't have proper RS-422 connectionю

# Features

* Automatic port opening & closing
* 5 user-defined buttons + 1 checkbox
* User-editable commands for all buttons

# Setup and usage

This remote's default layout uses _Sony UVW-1200_ Betacam SP VCR command set. 

<img width="844" height="403" alt="uvw1200" src="https://github.com/user-attachments/assets/8f6ceac7-750c-428f-bc54-05fe206ae2de" />

User editable settings are stored in **config.properties**

The default button order is:

<img width="839" height="346" alt="def" src="https://github.com/user-attachments/assets/ab7109c0-89bf-4b7e-bb6b-2e12647ef524" />

<hr>

In order to use customize this program you need to:

1. **Set up user buttons:**
   * _buttonX_name_ is a button **display name** (where X - is a button number)
   * _buttonX_cmd_ is a **button command** (where X - is a button number)

2. **Set up user checkbox:**
    * user checkbox **asserts RTS**
    * _optional1_name_ is a checkbox **display name**
    * _optional1_engaged_ set up checkbox status _(checked/unchecked)_ be default

3. **Redefine basic commands**

<hr>

Selected port will be opened **automatically** if you press any button. You will get a message if port can't be opened, there's an issue with output stream, etc.

For example:

<img width="632" height="311" alt="err" src="https://github.com/user-attachments/assets/fadab213-a1f7-4614-b5ed-6eb3606e530e" />

